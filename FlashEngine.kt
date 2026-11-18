package com.dvm.imager

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

enum class Phase { IDLE, WRITING, VERIFYING, DONE, FAILED, CANCELLED }

data class FlashProgress(
    val phase: Phase = Phase.IDLE,
    val fraction: Float = 0f, // 0..1 for the current phase, -1 = unknown
    val bytes: Long = 0L,
    val speedMBs: Float = 0f,
    val message: String = "",
    val sourceSha: String? = null,
    val readbackSha: String? = null
)

class FlashResult(val sourceSha: String, val readbackSha: String, val bytes: Long) {
    val match: Boolean get() = sourceSha == readbackSha
}

object FlashEngine {
    const val CHUNK_BYTES = 128 * 1024
    private const val MAX_CARD_BYTES = 512L * 1000 * 1000 * 1000

    suspend fun flash(
        ctx: Context,
        image: ImageInfo,
        dev: UsbMassStorage,
        onProgress: (FlashProgress) -> Unit
    ): FlashResult {
        val bs = dev.blockSize
        if (CHUNK_BYTES % bs != 0) throw IOException("Unsupported sector size: $bs bytes")
        if (dev.capacityBytes > MAX_CARD_BYTES) {
            throw IOException("This device is larger than 512 GB. Refusing to write: it is probably not a microSD card.")
        }
        if (!image.compressed && image.sizeBytes > dev.capacityBytes) {
            throw IOException("The image (${fmtBytes(image.sizeBytes)}) is larger than the card (${fmtBytes(dev.capacityBytes)}).")
        }

        val chunk = ByteArray(CHUNK_BYTES)
        val srcMd = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var lba = 0L
        val t0 = System.nanoTime()
        var lastEmit = 0L

        fun speed(bytes: Long): Float {
            val s = (System.nanoTime() - t0) / 1e9f
            return if (s > 0.2f) bytes / 1e6f / s else 0f
        }

        val opened = ImageSource.open(ctx, image)
        val writeNote = if (image.compressed) "Writing. Percent = compressed data consumed." else "Writing the image to the card."
        onProgress(FlashProgress(Phase.WRITING, 0f, 0, 0f, writeNote))

        opened.stream.use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = readFully(input, chunk)
                if (n <= 0) break
                srcMd.update(chunk, 0, n)
                val sectors = (n + bs - 1) / bs
                val padded = sectors * bs
                if (n < padded) java.util.Arrays.fill(chunk, n, padded, 0.toByte())
                if (lba + sectors > dev.blockCount) {
                    throw IOException("The image is larger than the card. The card is now incomplete.")
                }
                dev.writeBlocks(lba, sectors, chunk, 0)
                lba += sectors
                written += n

                val now = System.nanoTime()
                if (now - lastEmit > 200_000_000L) {
                    lastEmit = now
                    val frac = when {
                        image.sizeBytes <= 0 -> -1f
                        image.compressed -> minOf(1f, opened.counting.count.toFloat() / image.sizeBytes)
                        else -> minOf(1f, written.toFloat() / image.sizeBytes)
                    }
                    onProgress(FlashProgress(Phase.WRITING, frac, written, speed(written), writeNote))
                }
                if (n < CHUNK_BYTES) break
            }
        }
        if (written == 0L) throw IOException("The image file is empty")

        onProgress(FlashProgress(Phase.WRITING, 1f, written, speed(written), "Flushing the card's write cache."))
        dev.synchronizeCache()
        val sourceSha = hex(srcMd.digest())

        // ---- Verification: read the card back and hash the same number of bytes ----
        val dstMd = MessageDigest.getInstance("SHA-256")
        var remaining = written
        var vLba = 0L
        var verified = 0L
        val v0 = System.nanoTime()
        lastEmit = 0L
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val want = minOf(remaining, CHUNK_BYTES.toLong()).toInt()
            val sectors = (want + bs - 1) / bs
            dev.readBlocks(vLba, sectors, chunk, 0)
            dstMd.update(chunk, 0, want)
            vLba += sectors
            remaining -= want
            verified += want
            val now = System.nanoTime()
            if (now - lastEmit > 200_000_000L) {
                lastEmit = now
                val s = (now - v0) / 1e9f
                onProgress(
                    FlashProgress(
                        Phase.VERIFYING, verified.toFloat() / written, verified,
                        if (s > 0.2f) verified / 1e6f / s else 0f,
                        "Reading the card back and comparing SHA-256."
                    )
                )
            }
        }
        return FlashResult(sourceSha, hex(dstMd.digest()), written)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
