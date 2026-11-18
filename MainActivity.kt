package com.dvm.imager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) vm.onImagePicked(uri)
        }

    private val pickTree =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uri = res.data?.data
            if (res.resultCode == RESULT_OK && uri != null) vm.onTreePicked(uri)
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MainViewModel.ACTION_USB_PERMISSION) vm.onUsbPermissionResult()
            else vm.refreshAll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Broadcasts are not guaranteed on every phone, so also re-check the real
        // state every 2 seconds while the app is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    vm.refreshAll()
                    delay(2000)
                }
            }
        }

        setContent {
            DvmTheme {
                App(
                    vm = vm,
                    onSelectSd = {
                        val intent = SdSlot.intentToSelect(this)
                        if (intent == null) {
                            Toast.makeText(this, "No mounted SD card found", Toast.LENGTH_SHORT).show()
                        } else {
                            pickTree.launch(intent)
                        }
                    },
                    onPickImage = { pickImage.launch(arrayOf("*/*")) }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val media = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        val usb = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(MainViewModel.ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(this, receiver, media, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, receiver, usb, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }
}
