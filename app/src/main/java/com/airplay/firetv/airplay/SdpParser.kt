package com.airplay.firetv.airplay

data class AudioParams(
    val sampleRate: Int,
    val channelCount: Int,
    val audioSpecificConfig: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioParams
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (!audioSpecificConfig.contentEquals(other.audioSpecificConfig)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + channelCount
        result = 31 * result + audioSpecificConfig.contentHashCode()
        return result
    }
}

data class SdpMedia(
    val mediaType: String,
    val port: Int,
    val protocol: String,
    val payloadTypes: List<Int>
) {
    val rtpmap: MutableMap<Int, String> = mutableMapOf()
    val fmtp: MutableMap<Int, String> = mutableMapOf()
    val attributes: MutableList<Pair<String, String>> = mutableListOf()
}

data class SdpSession(
    val origin: String? = null,
    val sessionName: String? = null,
    val connection: String? = null,
    val medias: List<SdpMedia> = emptyList(),
    val audioParams: AudioParams? = null
) {
    fun getVideoMedia(): SdpMedia? = medias.find { it.mediaType.equals("video", ignoreCase = true) }
    fun getAudioMedia(): SdpMedia? = medias.find { it.mediaType.equals("audio", ignoreCase = true) }
}

object SdpParser {

    fun parse(sdp: String): SdpSession {
        val lines = sdp.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        var origin: String? = null
        var sessionName: String? = null
        var connection: String? = null
        val medias = mutableListOf<SdpMedia>()
        var currentMedia: SdpMedia? = null

        for (line in lines) {
            if (line.length < 2 || line[1] != '=') continue
            val key = line[0]
            val value = line.substring(2)

            when (key) {
                'o' -> origin = value
                's' -> sessionName = value
                'c' -> {
                    connection = value
                    currentMedia?.attributes?.add("c" to value)
                }
                'm' -> {
                    val media = parseMediaLine(value)
                    currentMedia = media
                    medias.add(media)
                }
                'a' -> {
                    currentMedia?.let { media ->
                        parseAttribute(value, media)
                    }
                }
            }
        }

        val audioMedia = medias.find { it.mediaType.equals("audio", ignoreCase = true) }
        val audioParams = audioMedia?.let { parseAudioParams(it) }

        return SdpSession(origin, sessionName, connection, medias, audioParams)
    }

    private fun parseMediaLine(value: String): SdpMedia {
        val parts = value.split(" ", limit = 4)
        val mediaType = parts.getOrElse(0) { "" }.trim()
        val port = parts.getOrElse(1) { "0" }.trim().toIntOrNull() ?: 0
        val protocol = parts.getOrElse(2) { "" }.trim()
        val ptList = parts.getOrElse(3) { "" }
            .split(" ")
            .mapNotNull { it.trim().toIntOrNull() }

        return SdpMedia(mediaType, port, protocol, ptList)
    }

    private fun parseAttribute(value: String, media: SdpMedia) {
        if (value.startsWith("rtpmap:", ignoreCase = true)) {
            val rest = value.substring(7)
            val colonIdx = rest.indexOf(' ')
            if (colonIdx > 0) {
                val pt = rest.substring(0, colonIdx).toIntOrNull()
                val encoding = rest.substring(colonIdx + 1).trim()
                if (pt != null) {
                    media.rtpmap[pt] = encoding
                }
            }
        } else if (value.startsWith("fmtp:", ignoreCase = true)) {
            val rest = value.substring(5)
            val spaceIdx = rest.indexOf(' ')
            if (spaceIdx > 0) {
                val pt = rest.substring(0, spaceIdx).toIntOrNull()
                val params = rest.substring(spaceIdx + 1).trim()
                if (pt != null) {
                    media.fmtp[pt] = params
                }
            }
        } else {
            val colonIdx = value.indexOf(':')
            if (colonIdx > 0) {
                val attrName = value.substring(0, colonIdx)
                val attrValue = value.substring(colonIdx + 1)
                media.attributes.add(attrName to attrValue)
            } else {
                media.attributes.add(value to "")
            }
        }
    }

    fun parseAudioParams(media: SdpMedia): AudioParams? {
        // Find AAC payload type
        val aacPt = media.payloadTypes.firstOrNull { pt ->
            val rtpmap = media.rtpmap[pt]
            rtpmap?.contains("mpeg4-generic", ignoreCase = true) == true
        } ?: return null

        // Parse rtpmap: e.g., "mpeg4-generic/44100/2"
        val rtpmap = media.rtpmap[aacPt] ?: return null
        val rtpmapParts = rtpmap.split("/")
        val sampleRate = rtpmapParts.getOrNull(1)?.toIntOrNull() ?: 44100
        val channelCount = rtpmapParts.getOrNull(2)?.toIntOrNull() ?: 2

        // Parse fmtp for config= hex string
        val fmtpStr = media.fmtp[aacPt] ?: return null
        val fmtpParams = parseFmtpParams(fmtpStr)
        val configHex = fmtpParams["config"] ?: return null

        // Convert hex string to byte array
        val asc = hexStringToByteArray(configHex) ?: return null

        return AudioParams(sampleRate, channelCount, asc)
    }

    fun getH264FmtpParams(media: SdpMedia): Map<String, String> {
        val h264Pt = media.payloadTypes.firstOrNull { pt ->
            val rtpmap = media.rtpmap[pt]
            rtpmap?.contains("H264", ignoreCase = true) == true
        } ?: return emptyMap()

        val fmtpStr = media.fmtp[h264Pt] ?: return emptyMap()
        return parseFmtpParams(fmtpStr)
    }

    private fun parseFmtpParams(fmtpStr: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pairs = fmtpStr.split(";")
        for (pair in pairs) {
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val key = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                result[key] = value
            }
        }
        return result
    }

    private fun hexStringToByteArray(hex: String): ByteArray? {
        val cleaned = hex.trim().replace(" ", "")
        if (cleaned.length % 2 != 0) return null
        return try {
            ByteArray(cleaned.length / 2) { i ->
                cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
