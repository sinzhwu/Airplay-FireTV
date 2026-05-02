package com.airplay.firetv.airplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

object TimingHandler {

    private const val NTP_PORT = 6002
    private const val NTP_EPOCH_OFFSET = 2208988800L

    suspend fun start() {
        withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(NTP_PORT).apply {
                    soTimeout = 5000
                }
                Timber.i("NTP server started on port $NTP_PORT")

                val buffer = ByteArray(32)
                val packet = DatagramPacket(buffer, buffer.size)

                while (true) {
                    try {
                        socket.receive(packet)
                        handleRequest(socket, packet)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: java.net.SocketTimeoutException) {
                        // timeout, continue loop and check for cancellation
                    } catch (e: Exception) {
                        Timber.w(e, "NTP packet handling error")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "NTP server error")
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                Timber.i("NTP server stopped")
            }
        }
    }

    private fun handleRequest(socket: DatagramSocket, requestPacket: DatagramPacket) {
        val data = requestPacket.data
        val length = requestPacket.length
        if (length < 4) return

        // Simple NTP-like response
        // AirPlay timing protocol is simplified NTP:
        // Request: origin timestamp (8 bytes)
        // Response: origin timestamp + receive timestamp + transmit timestamp
        val response = ByteArray(32)
        val buf = ByteBuffer.wrap(response)

        // Copy origin timestamp from request
        val originTimestamp = System.nanoTime()
        val originSeconds = (System.currentTimeMillis() / 1000) + NTP_EPOCH_OFFSET
        val originFraction = ((System.currentTimeMillis() % 1000) * 4294967296L / 1000)

        // Li VN Mode = 0 3 4 (no warning, version 3, server mode)
        buf.put(0, (0 shl 6) or (3 shl 3) or 4)
        buf.put(1, 0) // stratum = 0 (unspecified)
        buf.put(2, 0) // poll interval
        buf.put(3, 0) // precision

        // root delay (4 bytes)
        buf.putInt(4, 0)
        // root dispersion (4 bytes)
        buf.putInt(8, 0)
        // reference id (4 bytes)
        buf.putInt(12, 0)

        // reference timestamp (8 bytes)
        buf.putInt(16, originSeconds.toInt())
        buf.putInt(20, originFraction.toInt())

        // origin timestamp (8 bytes) - copy from request
        if (data.size >= 8) {
            System.arraycopy(data, 0, response, 24, 8)
        } else {
            buf.putInt(24, originSeconds.toInt())
            buf.putInt(28, originFraction.toInt())
        }

        val responsePacket = DatagramPacket(
            response,
            response.size,
            requestPacket.address,
            requestPacket.port
        )
        socket.send(responsePacket)

        Timber.v("NTP response sent to ${requestPacket.address}:${requestPacket.port}")
    }

    /**
     * Convert system time to NTP timestamp format (64-bit: 32-bit seconds + 32-bit fraction)
     */
    fun toNtpTimestamp(millis: Long): Long {
        val seconds = (millis / 1000) + NTP_EPOCH_OFFSET
        val fraction = ((millis % 1000) * 4294967296L / 1000)
        return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
    }

    /**
     * Parse NTP timestamp from bytes
     */
    fun parseNtpTimestamp(data: ByteArray, offset: Int = 0): Long {
        if (data.size < offset + 8) return 0L
        val seconds = ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
        val fraction = ((data[offset + 4].toLong() and 0xFF) shl 24) or
                ((data[offset + 5].toLong() and 0xFF) shl 16) or
                ((data[offset + 6].toLong() and 0xFF) shl 8) or
                (data[offset + 7].toLong() and 0xFF)
        return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
    }
}
