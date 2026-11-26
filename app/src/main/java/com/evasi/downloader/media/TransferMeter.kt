package com.evasi.downloader.media

data class TransferStats(val speedBytesPerSecond: Long?, val etaSeconds: Long?)

/** Per-transfer throughput, measured with a monotonic clock. Reset by creating one meter for each item. */
class TransferMeter {
    private var lastBytes = 0L
    private var lastTime: Long? = null
    private var speed: Long? = null

    fun sample(bytes: Long, total: Long?, nowMs: Long): TransferStats {
        val last = lastTime
        if (last == null || bytes < lastBytes || nowMs < last) {
            lastTime = nowMs
            lastBytes = bytes
            speed = null
        } else if (nowMs - last >= 500) {
            val rate = ((bytes - lastBytes).toDouble() * 1000 / (nowMs - last)).toLong()
            speed = rate.takeIf { it > 0 }
            lastTime = nowMs
            lastBytes = bytes
        }
        val eta = if (total != null && total >= bytes) {
            if (total == bytes) 0L else speed?.let { kotlin.math.ceil((total - bytes).toDouble() / it).toLong() }
        } else null
        return TransferStats(speed, eta)
    }
}

/** Split audio/video downloads report byte counters independently. Retries may repeat earlier counters. */
internal class NativeTransferProgress(
    private val estimate: Long?,
    private val expectedStreams: Int?,
    private val emit: (Long, Long?) -> Unit,
) {
    private val streams = mutableMapOf<String, Long>()
    private val totals = mutableMapOf<String, Long>()

    fun update(line: String) {
        if (!line.startsWith("bytes ")) return
        val fields = line.split(' ')
        val bytes = fields.getOrNull(2)?.toLongOrNull()?.takeIf { it >= 0 } ?: return
        val id = fields[1]
        streams[id] = maxOf(bytes, streams[id] ?: 0)
        val downloaded = streams.values.sum()
        fields.getOrNull(3)?.toLongOrNull()?.takeIf { it >= bytes }?.let { totals[id] = it }
        val knownTotal = if (expectedStreams != null && streams.size == expectedStreams && totals.size == expectedStreams)
            totals.values.sum() else estimate
        emit(downloaded, knownTotal?.takeIf { it >= downloaded })
    }

    fun complete(fileBytes: Long) {
        val downloaded = streams.values.sum().takeIf { it > 0 } ?: fileBytes
        emit(downloaded, downloaded)
    }
}
