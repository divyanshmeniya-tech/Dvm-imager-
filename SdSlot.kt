package com.dvm.imager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

enum class SlotState { NOT_DETECTED, ACCESS_NEEDED, ACCESS_GRANTED }

data class SlotInfo(
    val state: SlotState = SlotState.NOT_DETECTED,
    val name: String? = null,
    val totalBytes: Long = -1,
    val freeBytes: Long = -1,
    val writableAsFiles: Boolean = false,
    val message: String = "Android reports no mounted removable storage. Insert a MicroSD card in the phone's slot.",
    val warning: String? = null
)

/**
 * Built-in SD slot support. This only ever gives FILE access (Storage Access
 * Framework). It can never write a raw disk image: Android blocks raw
 * block-device access for normal apps.
 */
object SdSlot {
    private const val PREFS = "dvm"
    private const val KEY_TREE = "sd_tree_uri"

    fun removableVolume(ctx: Context): StorageVolume? {
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.storageVolumes.firstOrNull {
            it.isRemovable && !it.isPrimary && it.state == Environment.MEDIA_MOUNTED
        }
    }

    fun intentToSelect(ctx: Context): Intent? =
        removableVolume(ctx)?.createOpenDocumentTreeIntent()

    private fun savedTree(ctx: Context): Uri? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let { Uri.parse(it) }

    fun saveTree(ctx: Context, uri: Uri) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
    }

    /** True if the picked folder lives on this volume (document id starts with the volume UUID). */
    fun treeMatchesVolume(tree: Uri, volume: StorageVolume): Boolean {
        val uuid = volume.uuid ?: return false
        val docId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: Exception) {
            return false
        }
        return docId.startsWith("$uuid:", ignoreCase = true)
    }

    private fun hasPersisted(ctx: Context, tree: Uri): Boolean =
        ctx.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }

    private fun volumeDir(ctx: Context, v: StorageVolume): File? {
        val vPath: String? = if (Build.VERSION.SDK_INT >= 30) v.directory?.path else null
        return ctx.getExternalFilesDirs(null).filterNotNull().firstOrNull { f ->
            Environment.getExternalStorageState(f) == Environment.MEDIA_MOUNTED &&
                runCatching { Environment.isExternalStorageRemovable(f) }.getOrDefault(false) &&
                (vPath == null || f.path.startsWith(vPath))
        }
    }

    fun query(ctx: Context, usbStoragePresent: Boolean): SlotInfo {
        val volume = removableVolume(ctx) ?: return SlotInfo()
        val name = volume.getDescription(ctx) ?: "Removable storage"

        var total = -1L
        var free = -1L
        volumeDir(ctx, volume)?.let { dir ->
            runCatching {
                val st = StatFs(dir.path)
                total = st.totalBytes
                free = st.availableBytes
            }
        }

        val tree = savedTree(ctx)
        val doc = if (tree != null && treeMatchesVolume(tree, volume) && hasPersisted(ctx, tree)) {
            runCatching { DocumentFile.fromTreeUri(ctx, tree) }.getOrNull()
        } else null
        val accessOk = doc != null && doc.exists() && doc.canRead()
        val canWrite = doc?.canWrite() == true

        var warning: String? = null
        if (usbStoragePresent || name.contains("usb", ignoreCase = true)) {
            warning = "A USB storage device is attached or the volume looks like USB storage. " +
                "Unplug USB readers to be sure this is the phone's built-in slot."
        }

        return if (accessOk) {
            SlotInfo(
                SlotState.ACCESS_GRANTED, name, total, free, canWrite,
                "Android mounted the card and this app has been granted access.", warning
            )
        } else {
            SlotInfo(
                SlotState.ACCESS_NEEDED, name, total, free, false,
                "Android sees a mounted card, but this app has not been granted access yet.", warning
            )
        }
    }
}
