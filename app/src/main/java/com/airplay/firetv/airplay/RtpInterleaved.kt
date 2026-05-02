package com.airplay.firetv.airplay

import timber.log.Timber
import java.io.InputStream

object RtpInterleaved {

    private const val MAX_FRAME_SIZE = 2 * 1024 * 1024 // 2MB safety limit
    private const val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    /**
     * Audio RTP时钟频率，默认44100Hz。
     * 在SDP ANNOUNCE解析后应更新为实际的sampleRate（如44100或48000）。
     */
    @Volatile
    var audioClockRate: Int = 44100

    fun readLoop(
        input: InputStream,
        videoCallback: (ByteArray, Long) -> Unit,
        audioCallback: (ByteArray, Long) -> Unit
    ) {
        try {
            while (true) {
                // Read $ marker
                val marker = input.read()
                if (marker == -1) break
                if (marker != 0x24) { // '$'
                    continue
                }

                // Read channel id
                val channelId = input.read()
                if (channelId == -1) break

                // Read length (big-endian 2 bytes)
                val lenHigh = input.read()
                val lenLow = input.read()
                if (lenHigh == -1 || lenLow == -1) break
                val length = ((lenHigh and 0xFF) shl 8) or (lenLow and 0xFF)

                if (length > MAX_FRAME_SIZE) {
                    Timber.w("RTP frame too large: $length bytes, skipping")
                    var remaining = length
                    val skipBuf = ByteArray(4096)
                    while (remaining > 0) {
                        val toRead = minOf(skipBuf.size, remaining)
                        val read = input.read(skipBuf, 0, toRead)
                        if (read == -1) break
                        remaining -= read
                    }
                    continue
                }

                val payload = ByteArray(length)
                var read = 0
                while (read < length) {
                    val r = input.read(payload, read, length - read)
                    if (r == -1) {
                        Timber.w("Incomplete RTP frame read")
                        return
                    }
                    read += r
                }

                val rtpPacket = RtpPacket.parse(payload) ?: continue

                when (channelId) {
                    0 -> processVideoRtpFrame(rtpPacket, videoCallback)
                    2 -> {
                        // Audio timestamp: RTP clock for AAC uses the audio sample rate clock
                        // Convert from sample count to microseconds using the dynamic clock rate
                        val ptsUs = rtpPacket.timestamp * 1_000_000L / audioClockRate
                        audioCallback(rtpPacket.payload, ptsUs)
                    }
                    // channel 1 and 3 are RTCP, ignore for now
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "RTP interleaved read loop error")
        }
    }

    private fun processVideoRtpFrame(
        rtpPacket: RtpPacket,
        callback: (ByteArray, Long) -> Unit
    ) {
        if (rtpPacket.payload.isEmpty()) return

        val nalType = rtpPacket.payload[0].toInt() and 0x1F
        val ptsUs = rtpPacket.timestamp * 1_000_000L / 90_000L

        when {
            // Single NAL unit (types 1-23)
            nalType in 1..23 -> {
                val nalWithStartCode = START_CODE + rtpPacket.payload
                callback(nalWithStartCode, ptsUs)
            }

            // FU-A fragmentation (type 28)
            nalType == 28 -> handleFuA(rtpPacket, callback, ptsUs)

            else -> {
                Timber.d("Unhandled NAL type: $nalType")
            }
        }
    }

    private val fuABuffer = mutableMapOf<Int, MutableList<ByteArray>>()
    private val fuAStartReceived = mutableMapOf<Int, Boolean>()

    private fun handleFuA(
        rtpPacket: RtpPacket,
        callback: (ByteArray, Long) -> Unit,
        ptsUs: Long
    ) {
        if (rtpPacket.payload.size < 2) return

        val fuIndicator = rtpPacket.payload[0].toInt() and 0xFF
        val fuHeader = rtpPacket.payload[1].toInt() and 0xFF

        val startBit = (fuHeader and 0x80) != 0
        val endBit = (fuHeader and 0x40) != 0
        val nalType = fuHeader and 0x1F

        val payloadData = rtpPacket.payload.copyOfRange(2, rtpPacket.payload.size)

        if (startBit) {
            // Start of fragmented NAL
            val nalHeader = ((fuIndicator and 0xE0) or nalType).toByte()
            val fragmentList = mutableListOf<ByteArray>()
            fragmentList.add(byteArrayOf(nalHeader))
            fragmentList.add(payloadData)
            fuABuffer[rtpPacket.sequenceNumber] = fragmentList
            fuAStartReceived[rtpPacket.sequenceNumber] = true
        } else {
            // Middle or end fragment
            val seqKey = fuABuffer.keys.firstOrNull() ?: return
            if (!fuAStartReceived[seqKey]!!) return

            fuABuffer[seqKey]?.add(payloadData)

            if (endBit) {
                // Reassemble complete NAL
                val fragments = fuABuffer.remove(seqKey) ?: return
                fuAStartReceived.remove(seqKey)

                val totalSize = fragments.sumOf { it.size }
                val completeNal = ByteArray(4 + totalSize)
                System.arraycopy(START_CODE, 0, completeNal, 0, 4)

                var offset = 4
                for (fragment in fragments) {
                    System.arraycopy(fragment, 0, completeNal, offset, fragment.size)
                    offset += fragment.size
                }

                callback(completeNal, ptsUs)
            }
        }
    }
}
