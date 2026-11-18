package com.dvm.imager

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScsiException(message: String, val senseKey: Int = -1) : IOException(message)

/**
 * Minimal USB Mass Storage driver (Bulk-Only Transport + SCSI block commands),
 * implemented in user space with Android's USB host API. No root needed.
 * The user must grant USB permission for the specific device.
 */
class UsbMassStorage private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint
) : Closeable {

    var blockSize: Int = 512
        private set
    var blockCount: Long = 0
        private set
    var vendor: String = ""
        private set
    var product: String = ""
        private set
    var removable: Boolean = false
        private set

    val capacityBytes: Long get() = blockCount * blockSize

    private var tag = 1

    companion object {
        private const val CMD_TIMEOUT = 5000
        private const val DATA_TIMEOUT = 20000
        private const val XFER = 16384 // one USB transfer; multiple of 512 and 64

        private class Found(val itf: UsbInterface, val inEp: UsbEndpoint, val outEp: UsbEndpoint)

        private fun find(device: UsbDevice): Found? {
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                if (itf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    itf.interfaceSubclass == 6 && itf.interfaceProtocol == 0x50
                ) {
                    var inEp: UsbEndpoint? = null
                    var outEp: UsbEndpoint? = null
                    for (e in 0 until itf.endpointCount) {
                        val ep = itf.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                        }
                    }
                    if (inEp != null && outEp != null) return Found(itf, inEp, outEp)
                }
            }
            return null
        }

        fun isMassStorage(device: UsbDevice): Boolean = find(device) != null

        fun open(manager: UsbManager, device: UsbDevice): UsbMassStorage {
            val f = find(device) ?: throw IOException("Not a USB mass-storage (SCSI, bulk-only) device")
            val conn = manager.openDevice(device) ?: throw IOException("Could not open the USB device (permission missing?)")
            if (!conn.claimInterface(f.itf, true)) {
                conn.close()
                throw IOException("Could not claim the USB interface")
            }
            val dev = UsbMassStorage(conn, f.itf, f.inEp, f.outEp)
            try {
                dev.initialize()
            } catch (e: Exception) {
                dev.close()
                throw e
            }
            return dev
        }
    }

    // ---------- public block API ----------

    fun readBlocks(lba: Long, count: Int, buf: ByteArray, off: Int) = rw(0x28, lba, count, buf, off, true)

    fun writeBlocks(lba: Long, count: Int, buf: ByteArray, off: Int) = rw(0x2A, lba, count, buf, off, false)

    fun synchronizeCache() {
        try {
            execute(ByteArray(10).also { it[0] = 0x35 }, 0, false, null, 0)
        } catch (e: ScsiException) {
            // Some readers do not implement this command; ignore.
        }
    }

    override fun close() {
        try {
            connection.releaseInterface(iface)
        } catch (_: Exception) {
        }
        connection.close()
    }

    // ---------- SCSI ----------

    private fun initialize() {
        var ready = false
        var last: ScsiException? = null
        for (attempt in 0 until 10) {
            try {
                execute(ByteArray(6), 0, false, null, 0) // TEST UNIT READY
                ready = true
                break
            } catch (e: ScsiException) {
                last = e
                Thread.sleep(300)
            }
        }
        if (!ready) throw IOException("Card not ready: ${last?.message}")

        val inq = ByteArray(36)
        execute(byteArrayOf(0x12, 0, 0, 0, 36, 0), 36, true, inq, 0)
        removable = (inq[1].toInt() and 0x80) != 0
        vendor = String(inq, 8, 8, Charsets.US_ASCII).trim()
        product = String(inq, 16, 16, Charsets.US_ASCII).trim()

        val cap = ByteArray(8)
        execute(ByteArray(10).also { it[0] = 0x25 }, 8, true, cap, 0)
        val bb = ByteBuffer.wrap(cap).order(ByteOrder.BIG_ENDIAN)
        val last32 = bb.getInt(0).toLong() and 0xFFFFFFFFL
        val bs = bb.getInt(4)
        if (last32 == 0xFFFFFFFFL) throw IOException("Devices over 2 TiB are not supported")
        if (bs <= 0) throw IOException("Device reported an invalid block size")
        blockCount = last32 + 1
        blockSize = bs
    }

    private fun rw(op: Int, lba: Long, count: Int, buf: ByteArray, off: Int, dataIn: Boolean) {
        val cb = ByteArray(10)
        cb[0] = op.toByte()
        cb[2] = (lba shr 24).toByte()
        cb[3] = (lba shr 16).toByte()
        cb[4] = (lba shr 8).toByte()
        cb[5] = lba.toByte()
        cb[7] = (count shr 8).toByte()
        cb[8] = count.toByte()
        try {
            execute(cb, count * blockSize, dataIn, buf, off)
        } catch (e: ScsiException) {
            if (e.senseKey == 6) execute(cb, count * blockSize, dataIn, buf, off) else throw e
        }
    }

    private class CmdResult(val status: Int, val dataFailed: Boolean)

    private fun rawCommand(cb: ByteArray, len: Int, dataIn: Boolean, data: ByteArray?, off: Int): CmdResult {
        val raw = ByteArray(31)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val t = tag++
        bb.putInt(0, 0x43425355) // 'USBC'
        bb.putInt(4, t)
        bb.putInt(8, len)
        raw[12] = if (dataIn) 0x80.toByte() else 0x00.toByte()
        raw[13] = 0
        raw[14] = cb.size.toByte()
        System.arraycopy(cb, 0, raw, 15, cb.size)

        val sent = connection.bulkTransfer(epOut, raw, raw.size, CMD_TIMEOUT)
        if (sent != raw.size) {
            reset()
            throw IOException("Sending the USB command failed")
        }
        var dataFailed = false
        if (len > 0 && data != null) {
            try {
                if (dataIn) transferIn(data, off, len) else transferOut(data, off, len)
            } catch (e: IOException) {
                dataFailed = true
                clearHalt(if (dataIn) epIn else epOut)
            }
        }
        return CmdResult(readCsw(t), dataFailed)
    }

    private fun execute(cb: ByteArray, len: Int, dataIn: Boolean, data: ByteArray?, off: Int) {
        val r = rawCommand(cb, len, dataIn, data, off)
        if (r.status == 0 && !r.dataFailed) return
        if (r.status == 2) {
            reset()
            throw IOException("USB phase error")
        }
        throw requestSense() ?: ScsiException("SCSI command failed (status ${r.status})")
    }

    private fun requestSense(): ScsiException? {
        val buf = ByteArray(18)
        return try {
            val r = rawCommand(byteArrayOf(0x03, 0, 0, 0, 18, 0), 18, true, buf, 0)
            if (r.status != 0 || r.dataFailed) null else {
                val key = buf[2].toInt() and 0x0F
                val asc = buf[12].toInt() and 0xFF
                val ascq = buf[13].toInt() and 0xFF
                ScsiException(describe(key, asc, ascq), key)
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun describe(key: Int, asc: Int, ascq: Int): String = when {
        key == 2 && asc == 0x3A -> "No card in the reader"
        key == 2 -> "Device not ready"
        key == 3 -> "Medium error (bad sector or failing card)"
        key == 4 -> "Hardware error in the reader or card"
        key == 6 -> "Unit attention (card was reset or changed)"
        key == 7 -> "Write-protected: check the lock switch on the card or adapter"
        else -> "SCSI error key=$key ASC=0x%02X ASCQ=0x%02X".format(asc, ascq)
    }

    // ---------- Bulk-Only Transport ----------

    private fun readCsw(expectedTag: Int): Int {
        val csw = ByteArray(13)
        var r = connection.bulkTransfer(epIn, csw, csw.size, CMD_TIMEOUT)
        if (r < 0) {
            clearHalt(epIn)
            r = connection.bulkTransfer(epIn, csw, csw.size, CMD_TIMEOUT)
        }
        if (r != 13) {
            reset()
            throw IOException("Invalid USB status block (length $r)")
        }
        val bb = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != 0x53425355 || bb.getInt(4) != expectedTag) {
            reset()
            throw IOException("Invalid USB status block")
        }
        return csw[12].toInt() and 0xFF
    }

    private fun transferOut(buf: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            val n = minOf(XFER, len - done)
            val r = connection.bulkTransfer(epOut, buf, off + done, n, DATA_TIMEOUT)
            if (r < 0) throw IOException("USB write failed")
            done += r
        }
    }

    private fun transferIn(buf: ByteArray, off: Int, len: Int) {
        var done = 0
        var zeros = 0
        while (done < len) {
            val n = minOf(XFER, len - done)
            val r = connection.bulkTransfer(epIn, buf, off + done, n, DATA_TIMEOUT)
            if (r < 0) throw IOException("USB read failed")
            if (r == 0) {
                if (++zeros > 3) throw IOException("USB read stalled")
                continue
            }
            zeros = 0
            done += r
        }
    }

    private fun reset() {
        connection.controlTransfer(0x21, 0xFF, 0, iface.id, null, 0, CMD_TIMEOUT) // Bulk-Only Mass Storage Reset
        clearHalt(epIn)
        clearHalt(epOut)
    }

    private fun clearHalt(ep: UsbEndpoint) {
        connection.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, CMD_TIMEOUT) // CLEAR_FEATURE(ENDPOINT_HALT)
    }
}
