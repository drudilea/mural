package chat.mural.network

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** Full-duplex PCM16 mono audio: 16 kHz capture callbacks and 24 kHz streamed playback. */
internal interface PcmAudioIO {
    fun start(onCaptured: (ByteArray) -> Unit)
    fun play(pcm24k: ByteArray)
    fun flushPlayback()
    fun stop()
}

/** RMS of little-endian 16-bit samples, normalized to 0..1. */
internal fun pcm16Level(pcm: ByteArray): Double {
    if (pcm.size < 2) return 0.0
    var sum = 0.0; var count = 0; var i = 0
    while (i + 1 < pcm.size) {
        val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble() / 32768.0
        sum += sample * sample; count++; i += 2
    }
    return sqrt(sum / count)
}

internal class AndroidPcmAudioIO : PcmAudioIO {
    private val generation = AtomicInteger()
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    @SuppressLint("MissingPermission") // The transport checks RECORD_AUDIO before calling start().
    override fun start(onCaptured: (ByteArray) -> Unit) {
        val id = generation.incrementAndGet()
        playbackQueue.clear()
        val minRecord = AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minRecord, CAPTURE_CHUNK_BYTES * 4))
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); throw IllegalStateException("Microphone unavailable") }
        val minTrack = AudioTrack.getMinBufferSize(PLAYBACK_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PLAYBACK_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minTrack, PLAYBACK_RATE)) // half a second of 16-bit mono
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (error: Exception) {
            record.release() // Do not hold the microphone when playback cannot be opened.
            throw error
        }
        this.record = record; this.track = track
        record.startRecording(); track.play()
        captureThread = Thread({
            val buffer = ByteArray(CAPTURE_CHUNK_BYTES)
            while (generation.get() == id) {
                val read = runCatching { record.read(buffer, 0, buffer.size) }.getOrDefault(-1)
                if (read < 0) break
                if (read > 0) runCatching { onCaptured(buffer.copyOf(read)) }
            }
        }, "mural-gemini-capture").also { it.start() }
        playbackThread = Thread({
            while (generation.get() == id) {
                runCatching {
                    val chunk = playbackQueue.poll(200, TimeUnit.MILLISECONDS)
                    if (chunk != null && generation.get() == id) track.write(chunk, 0, chunk.size)
                }
            }
        }, "mural-gemini-playback").also { it.start() }
    }

    override fun play(pcm24k: ByteArray) { if (track != null) playbackQueue.offer(pcm24k) }

    override fun flushPlayback() {
        playbackQueue.clear()
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
    }

    override fun stop() {
        generation.incrementAndGet()
        playbackQueue.clear()
        // Stop the hardware first so a pending read() returns, then join, then release.
        record?.let { runCatching { it.stop() } }
        track?.let { runCatching { it.pause(); it.flush(); it.stop() } }
        captureThread?.join(500); captureThread = null
        playbackThread?.join(500); playbackThread = null
        record?.release(); record = null
        track?.release(); track = null
    }

    companion object {
        const val CAPTURE_RATE = 16_000
        const val PLAYBACK_RATE = 24_000
        /** 40 ms of 16-bit mono at 16 kHz. */
        const val CAPTURE_CHUNK_BYTES = CAPTURE_RATE * 2 * 40 / 1000
    }
}
