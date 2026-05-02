package com.airplay.firetv.airplay

import kotlinx.coroutines.*
import timber.log.Timber
import java.io.BufferedOutputStream
import java.net.Socket
import java.nio.ByteBuffer

class RtspSession(
    private val socket: Socket,
    private val callback: RtspCallback,
    private val scope: CoroutineScope
) {

    interface RtspCallback {
        fun onAnnounce(sdpBody: String)
        fun onRecord()
        fun onTeardown()
        fun onSetupComplete()
        fun onVideoRtpPacket(payload: ByteArray, ptsUs: Long)
        fun onAudioRtpPacket(payload: ByteArray, ptsUs: Long)
    }

    private var setupCount = 0
    private var cSeq = 0
    private var isRunning = true

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                val input = socket.getInputStream()
                val output = BufferedOutputStream(socket.getOutputStream())

                while (isRunning && socket.isConnected) {
                    if (setupCount < 2) {
                        // Phase 1: Text RTSP handshaking
                        handleTextPhase(input, output)
                    } else {
                        // Phase 2: Binary RTP interleaved reading
                        RtpInterleaved.readLoop(input, callback::onVideoRtpPacket, callback::onAudioRtpPacket)
                        break
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Timber.e(e, "RTSP session error")
                }
            } finally {
                try { socket.close() } catch (_: Exception) {}
                callback.onTeardown()
            }
        }
    }

    private suspend fun handleTextPhase(input: java.io.InputStream, output: BufferedOutputStream) {
        val lines = mutableListOf<String>()
        val buffer = StringBuilder()
        var contentLength = 0
        var foundContentLength = false

        while (isRunning) {
            val b = input.read()
            if (b == -1) {
                isRunning = false
                return
            }
            buffer.append(b.toChar())

            // Check for end of line (CRLF)
            if (buffer.endsWith("\r\n")) {
                val line = buffer.substring(0, buffer.length - 2)
                buffer.clear()

                if (line.isEmpty()) {
                    // End of headers, read body if needed
                    val request = parseRtspRequest(lines) ?: return
                    val body = if (foundContentLength && contentLength > 0) {
                        val bodyBytes = ByteArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val r = input.read(bodyBytes, read, contentLength - read)
                            if (r == -1) {
                                isRunning = false
                                return
                            }
                            read += r
                        }
                        String(bodyBytes, Charsets.UTF_8)
                    } else null

                    val fullRequest = request.copy(body = body)
                    val response = processRequest(fullRequest)

                    withContext(Dispatchers.IO) {
                        output.write(response.toBytes())
                        output.flush()
                    }
                    return
                } else {
                    lines.add(line)
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        foundContentLength = true
                    }
                }
            }
        }
    }

    private fun processRequest(req: RtspRequest): RtspResponse {
        Timber.d("RTSP ${req.method} ${req.uri}")

        val response = when (req.method.uppercase()) {
            "OPTIONS" -> handleOptions(req)
            "ANNOUNCE" -> handleAnnounce(req)
            "SETUP" -> handleSetup(req)
            "RECORD" -> handleRecord(req)
            "TEARDOWN" -> handleTeardown(req)
            "GET_PARAMETER" -> handleGetParameter(req)
            "SET_PARAMETER" -> handleSetParameter(req)
            "PAUSE" -> handlePause(req)
            "FLUSH" -> handleFlush(req)
            else -> RtspResponse.notFound()
        }

        // Echo CSeq
        req.headers["CSeq"]?.let { response.headers["CSeq"] = it }
        response.headers["Server"] = "AirPlay/220.68"

        return response
    }

    private fun handleOptions(req: RtspRequest): RtspResponse {
        return RtspResponse.ok(mutableMapOf(
            "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
        ))
    }

    private fun handleAnnounce(req: RtspRequest): RtspResponse {
        req.body?.let { callback.onAnnounce(it) }
        return RtspResponse.ok()
    }

    private fun handleSetup(req: RtspRequest): RtspResponse {
        val transport = req.headers["Transport"] ?: ""
        val isVideo = req.uri.contains("video", ignoreCase = true)
        
        return if (isVideo) {
            RtspResponse.ok(mutableMapOf(
                "Transport" to "RTP/AVP/TCP;interleaved=0-1",
                "Session" to "1"
            )).also {
                setupCount++
                checkSetupComplete()
            }
        } else {
            RtspResponse.ok(mutableMapOf(
                "Transport" to "RTP/AVP/TCP;interleaved=2-3",
                "Session" to "1"
            )).also {
                setupCount++
                checkSetupComplete()
            }
        }
    }

    private fun handleRecord(req: RtspRequest): RtspResponse {
        callback.onRecord()
        return RtspResponse.ok(mutableMapOf("Range" to "npt=0-"))
    }

    private fun handleTeardown(req: RtspRequest): RtspResponse {
        isRunning = false
        callback.onTeardown()
        return RtspResponse.ok()
    }

    private fun handleGetParameter(req: RtspRequest): RtspResponse {
        return RtspResponse.ok()
    }

    private fun handleSetParameter(req: RtspRequest): RtspResponse {
        return RtspResponse.ok()
    }

    private fun handlePause(req: RtspRequest): RtspResponse {
        return RtspResponse.ok()
    }

    private fun handleFlush(req: RtspRequest): RtspResponse {
        return RtspResponse.ok()
    }

    private fun checkSetupComplete() {
        if (setupCount >= 2) {
            callback.onSetupComplete()
        }
    }

    fun stop() {
        isRunning = false
        try { socket.close() } catch (_: Exception) {}
    }
}
