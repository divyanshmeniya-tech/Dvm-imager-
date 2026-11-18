package com.dvm.imager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

data class ImageInfo(val uri: Uri, val name: String, val sizeBytes: Long, val compressed: Boolean)

class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile
    var count: Long = 0
        private set

    override fun read(): Int {
        val r = super.read()
        if (r >= 0) count++
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = super.read(b, off, len)
        if (r > 0) count += r
        return r
    }
}

class OpenedImage(val stream: InputStream, val counting: CountingInputStream)

object ImageSource {
    fun describe(ctx: Context, uri: Uri): ImageInfo {
        var name = "image"
        var size = -1L
        ctx.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val lower = name.lowercase()
        val xz = lower.endsWith(".img.xz")
        require(xz || lower.endsWith(".img")) { "Please choose a .img or .img.xz file (you chose \"$name\")." }
        return ImageInfo(uri, name, size, xz)
    }

    fun open(ctx: Context, image: ImageInfo): OpenedImage {
        val raw = ctx.contentResolver.openInputStream(image.uri)
            ?: throw IOException("Could not open the image file")
        val counting = CountingInputStream(BufferedInputStream(raw, 1 shl 16))
        val stream: InputStream = if (image.compressed) XZInputStream(counting) else counting
        return OpenedImage(stream, counting)
    }
}
