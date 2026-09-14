package chat.mural.network

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmAudioIOTest {
    private fun samples(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v -> out[2 * i] = (v and 0xff).toByte(); out[2 * i + 1] = ((v shr 8) and 0xff).toByte() }
        return out
    }

    @Test fun silenceIsZeroAndFullScaleIsOne() {
        assertEquals(0.0, pcm16Level(samples(0, 0, 0, 0)), 0.0)
        assertEquals(1.0, pcm16Level(samples(32767, -32768, 32767, -32768)), 0.001)
        assertEquals(0.0, pcm16Level(ByteArray(1)), 0.0)
    }

    @Test fun levelIsRootMeanSquareOfNormalizedSamples() {
        assertEquals(0.5, pcm16Level(samples(16384, -16384)), 0.001)
    }

    @Test fun captureChunkIsFortyMillisecondsOfSixteenKilohertzMono() {
        assertEquals(1_280, AndroidPcmAudioIO.CAPTURE_CHUNK_BYTES)
    }
}
