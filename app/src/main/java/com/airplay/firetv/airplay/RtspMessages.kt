package com.airplay.firetv.airplay

data class RtspRequest(
    val method: String,
    val uri: String,
    val headers: Map<String, String>,
    val body: String?
)

data class RtspResponse(
    val statusCode: Int,
    val statusText: String,
    val headers: MutableMap<String, String> = mutableMapOf(),
    val body: String? = null
) {
    fun toBytes(): ByteArray {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 $statusCode $statusText\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        body?.let { sb.append(it) }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun ok(headers: Map<String, String> = emptyMap(), body: String? = null): RtspResponse {
            return RtspResponse(200, "OK", headers.toMutableMap(), body)
        }

        fun notFound(): RtspResponse {
            return RtspResponse(404, "Not Found")
        }

        fun serviceUnavailable(): RtspResponse {
            return RtspResponse(503, "Service Unavailable")
        }

        fun unauthorized(): RtspResponse {
            return RtspResponse(401, "Unauthorized", mutableMapOf("WWW-Authenticate" to "Digest realm=\"AirPlay\""))
        }
    }
}

fun parseRtspRequest(lines: List<String>): RtspRequest? {
    if (lines.isEmpty()) return null
    val firstLine = lines[0].trim()
    val parts = firstLine.split(" ", limit = 3)
    if (parts.size < 3) return null

    val method = parts[0]
    val uri = parts[1]
    val headers = mutableMapOf<String, String>()
    var body: String? = null
    var i = 1
    while (i < lines.size && lines[i].isNotBlank()) {
        val line = lines[i].trim()
        val colonIdx = line.indexOf(':')
        if (colonIdx > 0) {
            headers[line.substring(0, colonIdx).trim()] = line.substring(colonIdx + 1).trim()
        }
        i++
    }
    // Skip blank line
    i++
    if (i < lines.size) {
        body = lines.subList(i, lines.size).joinToString("\r\n")
    }

    return RtspRequest(method, uri, headers, body)
}
