package chat.mural.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import chat.mural.R
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Real-time voice over Gemini Live: WebSocket JSON, PCM capture and playback, and the phone's call audio route. */
class GeminiLiveTransport internal constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val readCredential: () -> String?,
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build(),
    private val endpoint: HttpUrl = GeminiLiveSocket.LIVE_ENDPOINT,
    private val audioFactory: () -> PcmAudioIO = { AndroidPcmAudioIO() },
) : VoiceTransport {
    constructor(context: Context, scope: CoroutineScope, credentials: CredentialStore) : this(context, scope, credentials::read)

    override var onEvent: ((JsonObject) -> Unit)? = null
    override var onFailure: ((String) -> Unit)? = null
    override var onLevels: ((Double, Double) -> Unit)? = null
    override val started: Boolean get() = session?.started?.get() == true
    override val isMuted: Boolean get() = session?.muted?.get() == true

    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mural-gemini-live") }.asCoroutineDispatcher()
    private val workerScope = CoroutineScope(SupervisorJob() + worker)
    @Volatile private var session: Session? = null

    private inner class Session(val audio: PcmAudioIO) {
        val translator = GeminiLiveTranslator()
        lateinit var socket: GeminiLiveSocket
        val started = AtomicBoolean(false)
        val muted = AtomicBoolean(false)
        val closing = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        var usageJob: Job? = null
        var meterJob: Job? = null
        @Volatile var inputLevel = 0.0
        @Volatile var outputLevel = 0.0
        var focusRequest: AudioFocusRequest? = null
        var previousMode = AudioManager.MODE_NORMAL
        var ownsAudioMode = false
        var ownsCommunicationRoute = false
        var legacyRoute: LegacyCommunicationAudioRoute? = null

        /** Retiring can run before connect assigns the socket. */
        fun cancelSocket() { if (::socket.isInitialized) socket.cancel() }
    }

    override suspend fun connect(api: LiveSessionProvider, instructions: String, history: JsonArray, language: String?) = withContext(worker) {
        session?.let { retire(it) }
        if (applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw LiveTransport.TransportException.Microphone(applicationContext.getString(R.string.error_transport_microphone))
        }
        val key = readCredential() ?: throw APIClient.APIException.MissingKey
        val current = Session(audioFactory())
        current.socket = GeminiLiveSocket(client, endpoint, key, current.translator,
            onInbound = { inbound -> workerScope.launch { receive(current, inbound) } },
            onClosed = { workerScope.launch { serverClosed(current) } },
            onFailure = { workerScope.launch { fail(current, applicationContext.getString(R.string.error_transport_channel_closed)) } })
        session = current
        try {
            acquireAudio(current)
            current.socket.open(instructions, READY_TIMEOUT_MILLISECONDS)
            current.translator.history(history)?.let { current.socket.send(it) }
            current.audio.start { chunk -> captured(current, chunk) }
            current.started.set(true)
            emit(current, buildJsonObject { put("type", "session.started") })
            current.meterJob = workerScope.launch {
                while (isActive && session === current) {
                    scope.launch { if (session === current) onLevels?.invoke(if (current.muted.get()) 0.0 else current.inputLevel, current.outputLevel) }
                    current.inputLevel *= LEVEL_DECAY; current.outputLevel *= LEVEL_DECAY
                    delay(METER_INTERVAL_MILLISECONDS)
                }
            }
            current.usageJob = workerScope.launch {
                while (isActive && session === current && !current.closing.get()) {
                    delay(USAGE_INTERVAL_MILLISECONDS)
                    if (session === current && !current.closing.get()) emit(current, current.translator.usage("session.usage.updated"))
                }
            }
        } catch (_: TimeoutCancellationException) {
            retire(current); throw LiveTransport.TransportException.Timeout(applicationContext.getString(R.string.error_transport_timeout))
        } catch (error: CancellationException) {
            retire(current); throw error
        } catch (error: APIClient.APIException) {
            retire(current); throw error
        } catch (error: LiveTransport.TransportException) {
            retire(current); throw error
        } catch (_: Throwable) {
            retire(current); throw LiveTransport.TransportException.Connection(applicationContext.getString(R.string.error_transport_connection))
        }
    }

    override fun send(event: JsonObject): Boolean {
        val current = session ?: return false
        if (!current.started.get() || current.closing.get()) return false
        val message = current.translator.outbound(event) ?: return true
        return current.socket.send(message)
    }

    override fun mute(muted: Boolean) { session?.muted?.set(muted) }

    /** Ends the session: stops audio, reports final seconds as session.closed, closes the socket. */
    override fun close() {
        val current = session ?: return
        if (!current.closing.compareAndSet(false, true)) return
        workerScope.launch {
            runCatching { current.audio.stop() }
            val closed = current.translator.usage("session.closed")
            current.socket.close()
            retire(current)
            scope.launch { onEvent?.invoke(closed) }
        }
    }

    override fun disconnect() {
        val current = session ?: return
        current.closing.set(true)
        session = null
        workerScope.launch { current.socket.cancel(); retire(current) }
    }

    private fun captured(current: Session, chunk: ByteArray) {
        if (session !== current || current.closing.get()) return
        current.inputLevel = maxOf(current.inputLevel, minOf(1.0, pcm16Level(chunk) * LEVEL_GAIN))
        if (current.muted.get()) return
        current.socket.send(current.translator.audioChunk(chunk))
    }

    private fun receive(current: Session, inbound: GeminiLiveTranslator.Inbound) {
        if (session !== current || current.closing.get()) return
        if (inbound.interrupted) current.audio.flushPlayback()
        for (pcm in inbound.audio) {
            current.audio.play(pcm)
            current.outputLevel = maxOf(current.outputLevel, minOf(1.0, pcm16Level(pcm) * LEVEL_GAIN))
        }
        inbound.events.forEach { emit(current, it) }
        if (inbound.goAway) close()
    }

    private fun serverClosed(current: Session) {
        if (session !== current || !current.closing.compareAndSet(false, true)) return
        runCatching { current.audio.stop() }
        val closed = current.translator.usage("session.closed")
        retire(current)
        scope.launch { onEvent?.invoke(closed) }
    }

    private fun fail(current: Session, message: String) {
        if (session !== current || current.closing.get() || !current.failed.compareAndSet(false, true)) return
        retire(current)
        scope.launch { onFailure?.invoke(message) }
    }

    private fun emit(current: Session, event: JsonObject) {
        scope.launch { if (session === current) onEvent?.invoke(event) }
    }

    /** Releases everything the session owns; safe to call more than once. */
    private fun retire(current: Session) {
        if (session === current) session = null
        current.usageJob?.cancel(); current.meterJob?.cancel()
        runCatching { current.audio.stop() }
        current.cancelSocket()
        releaseAudio(current)
        scope.launch { onLevels?.invoke(0.0, 0.0) }
    }

    // ponytail: audio focus and routing duplicated from LiveTransport; extract a shared VoiceAudioSession once the WebRTC path can be re-tested on a device.
    private fun acquireAudio(current: Session) {
        current.previousMode = audioManager.mode
        if (current.previousMode != AudioManager.MODE_NORMAL ||
            (Build.VERSION.SDK_INT < 31 && LegacyCommunicationAudioRoute.hasExistingSco(applicationContext, audioManager))) {
            throw LiveTransport.TransportException.AudioFocus(applicationContext.getString(R.string.error_transport_audio_focus))
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    workerScope.launch { fail(current, applicationContext.getString(R.string.error_transport_audio_interrupted)) }
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw LiveTransport.TransportException.AudioFocus(applicationContext.getString(R.string.error_transport_audio_focus))
        }
        current.focusRequest = request
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        current.ownsAudioMode = true
        if (Build.VERSION.SDK_INT >= 31) {
            val selected = selectCommunicationDevice(audioManager.communicationDevice, audioManager.availableCommunicationDevices,
                sameDevice = { left, right -> left.id == right.id }) { it.type }
            if (selected != null && audioManager.communicationDevice?.id != selected.id && audioManager.setCommunicationDevice(selected)) {
                current.ownsCommunicationRoute = true
            }
        } else {
            @Suppress("DEPRECATION") val previousSpeakerphone = audioManager.isSpeakerphoneOn
            current.legacyRoute = LegacyCommunicationAudioRoute(applicationContext, audioManager, workerScope, previousSpeakerphone) {
                workerScope.launch { fail(current, applicationContext.getString(R.string.error_transport_audio_stopped)) }
            }.also { it.start() }
        }
    }

    private fun releaseAudio(current: Session) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) { if (current.ownsCommunicationRoute) audioManager.clearCommunicationDevice() }
            else current.legacyRoute?.close(restoreSpeakerphone = true)
        }
        current.legacyRoute = null; current.ownsCommunicationRoute = false
        if (current.ownsAudioMode) { runCatching { audioManager.mode = current.previousMode }; current.ownsAudioMode = false }
        current.focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        current.focusRequest = null
    }

    companion object {
        private const val READY_TIMEOUT_MILLISECONDS = 20_000L
        private const val USAGE_INTERVAL_MILLISECONDS = 5_000L
        private const val METER_INTERVAL_MILLISECONDS = 100L
        private const val LEVEL_GAIN = 4.0
        private const val LEVEL_DECAY = 0.6
    }
}
