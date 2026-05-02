package com.airplay.firetv.airplay

import kotlinx.coroutines.*
import timber.log.Timber
import java.net.ServerSocket

class RtspHandler(
    private val callback: RtspSession.RtspCallback
) {

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private var serverSocket: ServerSocket? = null
    private var activeSession: RtspSession? = null
    private var isRunning = false

    fun start(port: Int = 7000) {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Timber.i("RTSP server listening on port $port")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Timber.i("RTSP client connected: ${socket.inetAddress}")

                    if (activeSession != null) {
                        Timber.w("Active session exists, rejecting new connection")
                        try {
                            val response = RtspResponse.serviceUnavailable()
                            response.headers["CSeq"] = "0"
                            socket.getOutputStream().write(response.toBytes())
                            socket.close()
                        } catch (e: Exception) {}
                        continue
                    }

                    activeSession = RtspSession(socket, callback, scope).also { session ->
                        session.start()
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Timber.e(e, "RTSP server error")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        activeSession?.stop()
        activeSession = null
        try { serverSocket?.close() } catch (e: Exception) {}
        supervisorJob.cancel()
    }
}
