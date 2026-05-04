package com.airplay.firetv.airplay

import android.content.Context
import android.view.Surface
import com.airplay.firetv.settings.AppSettings
import com.airplay.firetv.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

enum class ReceiverState {
    IDLE,
    ADVERTISING,
    CONNECTED,
    STREAMING,
    ERROR
}

class AirPlayReceiver(private val context: Context) : RtspSession.RtspCallback {

    private val _state = MutableStateFlow(ReceiverState.IDLE)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mdnsService: MdnsService? = null
    private var rtspHandler: RtspHandler? = null
    private var videoDecoder: VideoDecoder? = null
    private var currentSurface: Surface? = null

    private val crypto = AirPlayCrypto()
    private val audioDecoder = AudioDecoder()
    private val audioPlayer = AudioPlayer()

    private var sdpSession: SdpSession? = null

    init {
        // AudioDecoder -> AudioPlayer PCM 数据流
        audioDecoder.setCallback { pcmData, ptsUs ->
            audioPlayer.play(pcmData, ptsUs)
        }
    }

    fun start(settings: AppSettings) {
        if (_state.value != ReceiverState.IDLE) {
            Timber.w("Receiver already started, state=${_state.value}")
            return
        }

        receiverScope.launch {
            try {
                _state.value = ReceiverState.ADVERTISING
                Timber.i("AirPlayReceiver starting...")

                // Start RTSP handler first so the port is listening before
                // iOS discovers us via mDNS.
                rtspHandler = RtspHandler(this@AirPlayReceiver).apply {
                    start()
                }

                // Start mDNS advertising so iOS can discover this device
                mdnsService = MdnsService(context).apply {
                    val macAddress = NetworkUtils.getMacAddress(context)
                    val persistentUuid = NetworkUtils.generatePersistentUuid(macAddress)
                    start(settings.displayName, macAddress, persistentUuid.toString())
                }

                // Give jmDNS a moment to complete registration before reporting success.
                // This ensures iOS can see the service immediately after start returns.
                kotlinx.coroutines.delay(500)

                Timber.i("AirPlayReceiver started successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start AirPlayReceiver")
                _state.value = ReceiverState.ERROR
                stop()
            }
        }
    }

    fun stop() {
        Timber.i("AirPlayReceiver stopping...")

        receiverScope.launch {
            _streaming.value = false
            _state.value = ReceiverState.IDLE

            releaseVideoDecoder()
            releaseAudioComponents()

            try {
                mdnsService?.stop()
            } catch (e: Exception) {
                Timber.w(e, "Error stopping mDNS")
            }
            mdnsService = null

            try {
                rtspHandler?.stop()
            } catch (e: Exception) {
                Timber.w(e, "Error stopping RTSP handler")
            }
            rtspHandler = null

            crypto.clearKeys()

            Timber.i("AirPlayReceiver stopped")
        }
    }

    fun setOutputSurface(surface: Surface?) {
        currentSurface = surface
        if (surface != null) {
            videoDecoder?.release()
            videoDecoder = null
            // New decoder will be created when first NAL arrives
        } else {
            releaseVideoDecoder()
        }
    }

    fun release() {
        stop()
        receiverScope.cancel()
    }

    // --- RtspCallback implementation ---

    override fun onAnnounce(sdpBody: String) {
        Timber.d("ANNOUNCE received, parsing SDP...")
        try {
            val session = SdpParser.parse(sdpBody)
            sdpSession = session
            val videoMedia = session.getVideoMedia()
            val audioMedia = session.getAudioMedia()
            Timber.i("SDP parsed: video=${videoMedia != null}, audio=${audioMedia != null}")

            // 初始化音频解码器
            audioMedia?.let { media ->
                val params = SdpParser.parseAudioParams(media)
                if (params != null) {
                    Timber.i("Audio params: ${params.sampleRate}Hz, ${params.channelCount}ch, " +
                            "config=${params.audioSpecificConfig?.joinToString(" ") { "0x%02X".format(it) }}")
                    // 更新音频RTP时钟频率用于时间戳转换
                    RtpInterleaved.audioClockRate = params.sampleRate
                    audioDecoder.initialize(params)
                    audioPlayer.initialize(params.sampleRate, params.channelCount)
                } else {
                    Timber.w("Failed to parse audio params from SDP")
                }
            }

            // Video SPS/PPS will be parsed when first NAL units arrive
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse SDP")
        }
    }

    override fun onRecord() {
        Timber.i("RECORD received, streaming begins")
        receiverScope.launch {
            _streaming.value = true
            _state.value = ReceiverState.STREAMING
            audioDecoder.onStreamingStarted()
        }
    }

    override fun onTeardown() {
        Timber.i("TEARDOWN received")
        receiverScope.launch {
            _streaming.value = false
            _state.value = ReceiverState.ADVERTISING
            releaseVideoDecoder()
            releaseAudioComponents()
            sdpSession = null
        }
    }

    override fun onSetupComplete() {
        Timber.i("SETUP complete (video + audio)")
        receiverScope.launch {
            _state.value = ReceiverState.CONNECTED
        }
    }

    override fun onVideoRtpPacket(payload: ByteArray, ptsUs: Long) {
        val surface = currentSurface ?: return

        var decoder = videoDecoder
        if (decoder == null) {
            try {
                decoder = VideoDecoder(surface)
                videoDecoder = decoder
            } catch (e: Exception) {
                Timber.e(e, "Failed to create VideoDecoder")
                return
            }
        }

        decoder.decode(payload, ptsUs)
    }

    override fun onAudioRtpPacket(payload: ByteArray, ptsUs: Long) {
        audioDecoder.decode(payload, ptsUs)
    }

    // --- Private ---

    private fun releaseVideoDecoder() {
        try {
            videoDecoder?.release()
        } catch (e: Exception) {
            Timber.w(e, "Error releasing video decoder")
        }
        videoDecoder = null
    }

    private fun releaseAudioComponents() {
        try {
            audioDecoder.release()
        } catch (e: Exception) {
            Timber.w(e, "Error releasing audio decoder")
        }
        try {
            audioPlayer.release()
        } catch (e: Exception) {
            Timber.w(e, "Error releasing audio player")
        }
    }
}
