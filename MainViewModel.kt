package com.dvm.imager

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UsbInfo(
    val vendor: String,
    val product: String,
    val capacityBytes: Long,
    val blockSize: Int,
    val removable: Boolean
)

data class UsbUiState(
    val deviceName: String? = null,
    val deviceCount: Int = 0,
    val hasPermission: Boolean = false,
    val probing: Boolean = false,
    val info: UsbInfo? = null,
    val error: String? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.dvm.imager.USB_PERMISSION"
    }

    private val appCtx: Context = app.applicationContext
    private val usbManager = appCtx.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _slot = MutableStateFlow(SlotInfo())
    val slot: StateFlow<SlotInfo> = _slot.asStateFlow()
    private val _usb = MutableStateFlow(UsbUiState())
    val usb: StateFlow<UsbUiState> = _usb.asStateFlow()
    private val _image = MutableStateFlow<ImageInfo?>(null)
    val image: StateFlow<ImageInfo?> = _image.asStateFlow()
    private val _flash = MutableStateFlow(FlashProgress())
    val flash: StateFlow<FlashProgress> = _flash.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var flashJob: Job? = null
    private var probeJob: Job? = null
    private var failedDevice: String? = null

    private val isRunning: Boolean get() = flashJob?.isActive == true

    private fun massStorageDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { UsbMassStorage.isMassStorage(it) }

    fun refreshAll() {
        refreshSlot()
        refreshUsb()
    }

    fun dismissMessage() {
        _message.value = null
    }

    // ---------- Built-in SD slot ----------

    fun refreshSlot() {
        val usbPresent = massStorageDevices().isNotEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            _slot.value = SdSlot.query(appCtx, usbPresent)
        }
    }

    fun onTreePicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val vol = SdSlot.removableVolume(appCtx)
            if (vol == null) {
                _message.value = "No mounted SD card was found."
                return@launch
            }
            if (!SdSlot.treeMatchesVolume(uri, vol)) {
                _message.value = "That folder is not on the SD card. Choose a folder on the SD card."
                return@launch
            }
            try {
                appCtx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                _message.value = "Android did not grant lasting access: ${e.message}"
                return@launch
            }
            SdSlot.saveTree(appCtx, uri)
            _message.value = null
            _slot.value = SdSlot.query(appCtx, massStorageDevices().isNotEmpty())
        }
    }

    // ---------- Image ----------

    fun onImagePicked(uri: Uri) {
        if (isRunning) return
        try {
            _image.value = ImageSource.describe(appCtx, uri)
            _flash.value = FlashProgress()
            _message.value = null
        } catch (e: Exception) {
            _message.value = e.message ?: "Could not use that file."
        }
    }

    // ---------- USB reader ----------

    fun refreshUsb() {
        if (isRunning) return
        val devices = massStorageDevices()
        when {
            devices.isEmpty() -> {
                failedDevice = null
                probeJob?.cancel()
                _usb.value = UsbUiState()
            }
            devices.size > 1 -> {
                _usb.value = UsbUiState(
                    deviceCount = devices.size,
                    error = "More than one USB storage device is attached. Leave only the card reader connected."
                )
            }
            else -> {
                val d = devices[0]
                val has = usbManager.hasPermission(d)
                val cur = _usb.value
                val same = cur.deviceName == d.deviceName
                if (!has) {
                    _usb.value = UsbUiState(deviceName = d.deviceName, deviceCount = 1, hasPermission = false)
                } else if (same && (cur.info != null || cur.probing || failedDevice == d.deviceName)) {
                    if (!cur.hasPermission) _usb.value = cur.copy(hasPermission = true)
                } else {
                    probe(d)
                }
            }
        }
    }

    private fun probe(d: UsbDevice) {
        _usb.value = UsbUiState(deviceName = d.deviceName, deviceCount = 1, hasPermission = true, probing = true)
        probeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                UsbMassStorage.open(usbManager, d).use { dev ->
                    _usb.value = UsbUiState(
                        deviceName = d.deviceName, deviceCount = 1, hasPermission = true,
                        info = UsbInfo(dev.vendor, dev.product, dev.capacityBytes, dev.blockSize, dev.removable)
                    )
                }
            } catch (e: Exception) {
                failedDevice = d.deviceName
                _usb.value = UsbUiState(
                    deviceName = d.deviceName, deviceCount = 1, hasPermission = true,
                    error = e.message ?: "Could not read the card reader"
                )
            }
        }
    }

    fun requestUsbPermission() {
        val d = massStorageDevices().firstOrNull() ?: return
        val pi = PendingIntent.getBroadcast(
            appCtx, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(appCtx.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(d, pi)
    }

    fun onUsbPermissionResult() {
        failedDevice = null
        refreshUsb()
    }

    // ---------- Flashing ----------

    fun startFlash() {
        if (isRunning) return
        val img = _image.value ?: return
        val dev = massStorageDevices().singleOrNull()
        if (dev == null || !usbManager.hasPermission(dev)) {
            _message.value = "The USB card reader is not ready. Reconnect it and grant USB access."
            return
        }
        _message.value = null
        _flash.value = FlashProgress(Phase.WRITING, 0f, 0, 0f, "Opening the USB card reader.")
        flashJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    UsbMassStorage.open(usbManager, dev).use { storage ->
                        FlashEngine.flash(appCtx, img, storage) { p -> _flash.value = p }
                    }
                }
                _flash.value = if (result.match) {
                    FlashProgress(
                        Phase.DONE, 1f, result.bytes, 0f,
                        "Written and verified. The SHA-256 of the data read back from the card matches the image.",
                        result.sourceSha, result.readbackSha
                    )
                } else {
                    FlashProgress(
                        Phase.FAILED, 1f, result.bytes, 0f,
                        "Verification failed: the data on the card differs from the image. Do not use this card. Try again or use another card.",
                        result.sourceSha, result.readbackSha
                    )
                }
            } catch (e: CancellationException) {
                _flash.value = FlashProgress(Phase.CANCELLED, message = "Cancelled. The card is incomplete and will not boot.")
                throw e
            } catch (e: Throwable) {
                _flash.value = FlashProgress(Phase.FAILED, message = e.message ?: e.toString())
            }
        }
    }

    fun cancelFlash() {
        flashJob?.cancel()
    }
}
