package com.dvm.imager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

object C {
    val Bg = Color(0xFF0A0D14)
    val Surface = Color(0xFF121722)
    val Surface2 = Color(0xFF1A2130)
    val Line = Color(0xFF252D3F)
    val Mint = Color(0xFF5EEAD4)
    val Violet = Color(0xFFA78BFA)
    val Green = Color(0xFF4ADE80)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFF87171)
    val TextHi = Color(0xFFE8ECF4)
    val TextLo = Color(0xFF8B93A7)
}

fun fmtBytes(b: Long): String {
    if (b < 0) return "Not available"
    return when {
        b >= 1_000_000_000L -> "%.2f GB".format(b / 1e9)
        b >= 1_000_000L -> "%.1f MB".format(b / 1e6)
        else -> "$b B"
    }
}

@Composable
fun DvmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Mint, onPrimary = C.Bg,
            background = C.Bg, onBackground = C.TextHi,
            surface = C.Surface, onSurface = C.TextHi,
            surfaceVariant = C.Surface2, onSurfaceVariant = C.TextLo
        ),
        content = content
    )
}

// ---------- building blocks ----------

@Composable
fun Panel(modifier: Modifier = Modifier, border: Color = C.Line, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(C.Surface)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

@Composable
fun StatusHero(title: String, subtitle: String, color: Color, titleSize: Int = 28) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.20f), C.Surface)))
            .border(1.5.dp, color.copy(alpha = 0.75f), RoundedCornerShape(26.dp))
            .padding(horizontal = 22.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Spacer(Modifier.height(14.dp))
        Text(
            title, color = color, fontSize = titleSize.sp, fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center, lineHeight = (titleSize + 4).sp
        )
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = C.TextLo, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = C.TextHi) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = C.TextLo, fontSize = 14.sp)
        Text(
            value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 16.dp)
        )
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = C.Mint, contentColor = C.Bg,
            disabledContainerColor = C.Surface2, disabledContentColor = C.TextLo
        )
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, C.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = C.TextHi, disabledContentColor = C.TextLo)
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
}

@Composable
fun Header() {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(
            "DVM IMAGER",
            style = TextStyle(
                brush = Brush.horizontalGradient(listOf(C.Mint, C.Violet)),
                fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp
            )
        )
        Text("Flash Raspberry Pi OS for the VSM AI Robot", color = C.TextLo, fontSize = 13.sp)
    }
}

// ---------- screens ----------

@Composable
fun App(vm: MainViewModel, onSelectSd: () -> Unit, onPickImage: () -> Unit) {
    val slot by vm.slot.collectAsStateWithLifecycle()
    val usb by vm.usb.collectAsStateWithLifecycle()
    val image by vm.image.collectAsStateWithLifecycle()
    val flash by vm.flash.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val running = flash.phase == Phase.WRITING || flash.phase == Phase.VERIFYING
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        containerColor = C.Bg,
        bottomBar = {
            NavigationBar(containerColor = C.Surface) {
                val colors = NavigationBarItemDefaults.colors(
                    indicatorColor = C.Surface2, selectedTextColor = C.Mint, unselectedTextColor = C.TextLo
                )
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 }, colors = colors,
                    icon = { Text("💾") }, label = { Text("Built-in SD slot") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 }, colors = colors,
                    icon = { Text("🔌") }, label = { Text("USB OTG flash") }
                )
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Header()
            message?.let {
                Panel(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), border = C.Amber) {
                    Text(it, color = C.TextHi, fontSize = 14.sp)
                    Text("Dismiss", color = C.Amber, fontSize = 13.sp, modifier = Modifier.clickable { vm.dismissMessage() })
                }
            }
            if (tab == 0) SdTab(slot, onSelectSd)
            else FlashTab(
                image = image, usb = usb, f = flash, running = running,
                onPickImage = onPickImage,
                onGrantUsb = { vm.requestUsbPermission() },
                onStart = { vm.startFlash() },
                onCancel = { vm.cancelFlash() }
            )
        }
    }
}

@Composable
fun SdTab(s: SlotInfo, onSelect: () -> Unit) {
    val (title, sub, color) = when (s.state) {
        SlotState.ACCESS_GRANTED -> Triple("SD CARD DETECTED", s.message, C.Green)
        SlotState.ACCESS_NEEDED -> Triple("SD CARD NOT DETECTED", s.message + " Tap Select SD card.", C.Amber)
        SlotState.NOT_DETECTED -> Triple("SD CARD NOT DETECTED", s.message, C.Red)
    }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StatusHero(title, sub, color)
        Panel {
            val have = s.state != SlotState.NOT_DETECTED
            InfoRow("Card name", if (have) (s.name ?: "Unknown") else "None")
            InfoRow("Total space", if (have) fmtBytes(s.totalBytes) else "None")
            InfoRow("Available space", if (have) fmtBytes(s.freeBytes) else "None")
            InfoRow(
                "Used space",
                if (have && s.totalBytes >= 0 && s.freeBytes >= 0) fmtBytes(s.totalBytes - s.freeBytes) else "None"
            )
            InfoRow(
                "App access (SAF)",
                when (s.state) {
                    SlotState.ACCESS_GRANTED -> "Granted"
                    SlotState.ACCESS_NEEDED -> "Not granted"
                    SlotState.NOT_DETECTED -> "None"
                },
                if (s.state == SlotState.ACCESS_GRANTED) C.Green else C.TextHi
            )
            InfoRow(
                "Writable as files",
                if (s.state == SlotState.ACCESS_GRANTED) (if (s.writableAsFiles) "Yes" else "No") else "Unknown"
            )
        }
        s.warning?.let {
            Panel(border = C.Amber) { Text(it, color = C.TextHi, fontSize = 13.sp, lineHeight = 18.sp) }
        }
        PrimaryButton("Select SD card", enabled = s.state != SlotState.NOT_DETECTED, onClick = onSelect)
        Panel {
            Text("What this tab can and cannot do", color = C.TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "The phone's built-in slot gives this app file access only. Android blocks raw disk writes " +
                    "for apps, so Raspberry Pi OS cannot be flashed through this slot. Copying an .img file " +
                    "onto the card does not make it bootable. Use the USB OTG flash tab with a USB card reader.",
                color = C.TextLo, fontSize = 13.sp, lineHeight = 18.sp
            )
            Text(
                "On Android 11 and newer, Android may refuse the top level of the card. If so, open the card " +
                    "in the picker, create a folder (for example DVM) and choose that folder.",
                color = C.TextLo, fontSize = 13.sp, lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun FlashTab(
    image: ImageInfo?, usb: UsbUiState, f: FlashProgress, running: Boolean,
    onPickImage: () -> Unit, onGrantUsb: () -> Unit, onStart: () -> Unit, onCancel: () -> Unit
) {
    var confirm by remember { mutableStateOf(false) }
    val info = usb.info
    val canStart = image != null && info != null && usb.hasPermission && !running

    val (uTitle, uSub, uColor) = when {
        usb.deviceCount > 1 -> Triple("MULTIPLE USB STORAGE DEVICES", usb.error ?: "", C.Amber)
        usb.deviceName == null -> Triple(
            "USB CARD READER NOT DETECTED",
            "Plug a USB OTG microSD reader with a card inside into the phone.", C.Red
        )
        !usb.hasPermission -> Triple("USB READER FOUND", "Android needs your permission before this app can use it.", C.Amber)
        usb.probing -> Triple("READING CARD", "Talking to the reader.", C.Amber)
        usb.error != null -> Triple("USB READER ERROR", usb.error ?: "", C.Red)
        info != null -> Triple(
            "USB CARD READER DETECTED",
            "${(info.vendor + " " + info.product).trim()}, ${fmtBytes(info.capacityBytes)}", C.Green
        )
        else -> Triple("USB CARD READER NOT DETECTED", "", C.Red)
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Panel(border = C.Violet) {
            Text(
                "This tab writes to a card in a USB OTG reader. It does not write to the phone's built-in slot.",
                color = C.TextHi, fontSize = 13.sp, lineHeight = 18.sp
            )
        }

        // Step 1: image
        PrimaryButton("Select OS image", enabled = !running, onClick = onPickImage)
        if (image != null) {
            Panel {
                InfoRow("File", image.name)
                InfoRow("Size on disk", fmtBytes(image.sizeBytes))
                InfoRow("Type", if (image.compressed) ".img.xz (decompressed while writing)" else ".img (raw)")
            }
        }

        // Step 2: USB reader
        StatusHero(uTitle, uSub, uColor, titleSize = 20)
        if (usb.deviceName != null && !usb.hasPermission) {
            SecondaryButton("Grant USB access", onClick = onGrantUsb)
        }

        // Step 3: write
        PrimaryButton("Start writing", enabled = canStart, onClick = { confirm = true })
        if (running) SecondaryButton("Cancel", onClick = onCancel)

        // Progress
        val frameColor = when (f.phase) {
            Phase.DONE -> C.Green
            Phase.FAILED -> C.Red
            Phase.CANCELLED -> C.Amber
            else -> C.Line
        }
        Panel(border = frameColor) {
            Text(
                when (f.phase) {
                    Phase.IDLE -> "Ready"
                    Phase.WRITING -> "Step 1 of 2: writing"
                    Phase.VERIFYING -> "Step 2 of 2: verifying"
                    Phase.DONE -> "Done"
                    Phase.FAILED -> "Failed"
                    Phase.CANCELLED -> "Cancelled"
                },
                color = C.TextLo, fontSize = 13.sp
            )
            Text(
                if (f.fraction < 0f) "..." else "${(f.fraction * 100).toInt()}%",
                color = frameColor.takeIf { it != C.Line } ?: C.TextHi,
                fontSize = 54.sp, fontWeight = FontWeight.Black
            )
            if (running) {
                if (f.fraction < 0f) LinearProgressIndicator(Modifier.fillMaxWidth(), color = C.Mint, trackColor = C.Surface2)
                else LinearProgressIndicator(
                    progress = { f.fraction }, modifier = Modifier.fillMaxWidth(),
                    color = C.Mint, trackColor = C.Surface2
                )
            }
            if (f.phase != Phase.IDLE) {
                Text("${fmtBytes(f.bytes)} processed, %.1f MB/s".format(f.speedMBs), color = C.TextLo, fontSize = 13.sp)
            }
            if (f.message.isNotEmpty()) Text(f.message, color = C.TextHi, fontSize = 14.sp, lineHeight = 19.sp)
            if (f.sourceSha != null) {
                Spacer(Modifier.height(6.dp))
                Text("SHA-256 of the image data", color = C.TextLo, fontSize = 12.sp)
                Text(f.sourceSha, color = C.TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("SHA-256 read back from the card", color = C.TextLo, fontSize = 12.sp)
                Text(f.readbackSha ?: "", color = C.TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (running) {
                Text("Keep this screen open and the reader connected until it finishes.", color = C.Amber, fontSize = 13.sp)
            }
        }
    }

    if (confirm && image != null && info != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = C.Surface2,
            titleContentColor = C.TextHi,
            textContentColor = C.TextHi,
            title = { Text("Erase this card and write the image?") },
            text = {
                Text(
                    "Card: ${(info.vendor + " " + info.product).trim()} (${fmtBytes(info.capacityBytes)})\n" +
                        "Image: ${image.name}\n\n" +
                        "Everything on this card will be erased. Check that it is the right device." +
                        if (!info.removable) "\n\nWarning: this device does not report itself as removable. It may not be an SD card reader." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; onStart() }) {
                    Text("Erase and write", color = C.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = C.TextHi) } }
        )
    }
}
