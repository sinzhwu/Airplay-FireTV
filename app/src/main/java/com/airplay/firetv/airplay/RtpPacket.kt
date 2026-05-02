package com.airplay.firetv.airplay

data class RtpPacket(
    val version: Int,
    val padding: Boolean,
    val extension: Boolean,
    val csrcCount: Int,
    val marker: Boolean,
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RtpPacket
        return sequenceNumber == other.sequenceNumber && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = sequenceNumber
        result = 31 * result + timestamp.hashCode()
        return result
    }

    companion object {
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): RtpPacket? {
            if (length < 12) return null
            val firstByte = data[offset].toInt() and 0xFF
            val secondByte = data[offset + 1].toInt() and 0xFF

            val version = (firstByte shr 6) and 0x03
            val padding = ((firstByte shr 5) and 0x01) == 1
            val extension = ((firstByte shr 4) and 0x01) == 1
            val csrcCount = firstByte and 0x0F
            val marker = ((secondByte shr 7) and 0x01) == 1
            val payloadType = secondByte and 0x7F

            val sequenceNumber = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            val timestamp = ((data[offset + 4].toInt() and 0xFF) shl 24) or
                    ((data[offset + 5].toInt() and 0xFF) shl 16) or
                    ((data[offset + 6].toInt() and 0xFF) shl 8) or
                    (data[offset + 7].toInt() and 0xFF)
            val ssrc = ((data[offset + 8].toInt() and 0xFF) shl 24) or
                    ((data[offset + 9].toInt() and 0xFF) shl 16) or
                    ((data[offset + 10].toInt() and 0xFF) shl 8) or
                    (data[offset + 11].toInt() and 0xFF)

            val payload = data.copyOfRange(offset + 12, offset + length)

            return RtpPacket(
                version = version,
                padding = padding,
                extension = extension,
                csrcCount = csrcCount,
                marker = marker,
                payloadType = payloadType,
                sequenceNumber = sequenceNumber,
                timestamp = timestamp.toLong() and 0xFFFFFFFFL,
                ssrc = ssrc.toLong() and 0xFFFFFFFFL,
                payload = payload
            )
        }
    }
}
