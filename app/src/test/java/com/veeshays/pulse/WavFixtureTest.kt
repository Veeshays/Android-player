package com.veeshays.pulse

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Guards the bundled self-test fixture (res/raw/probe_tone.wav, mirrored
 * under test resources): valid RIFF/WAVE PCM16 mono 44.1kHz with consistent
 * sizes, so the on-device tone probe tests the engine — never the file.
 */
class WavFixtureTest {

    private fun bytes(): ByteArray {
        val url = javaClass.classLoader!!.getResource("probe_tone.wav")
            ?: error("fixture missing from test resources")
        return url.readBytes()
    }

    @Test fun header_isValidRiffWavePcm() {
        val b = ByteBuffer.wrap(bytes()).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(off: Int) = String(bytes(), off, 4, Charsets.US_ASCII)
        assertEquals("RIFF", tag(0))
        assertEquals("WAVE", tag(8))
        assertEquals("fmt ", tag(12))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(44100, b.getInt(24)) // sample rate
        assertEquals(16, b.getShort(34).toInt()) // bits
        assertEquals("data", tag(36))
    }

    @Test fun sizes_areConsistent() {
        val raw = bytes()
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val riffSize = b.getInt(4)
        val dataSize = b.getInt(40)
        assertEquals(raw.size - 8, riffSize)
        assertEquals(raw.size - 44, dataSize)
        assertEquals(5 * 44100 * 2, dataSize) // 5s mono 16-bit
        assertTrue(dataSize > 0)
    }

    @Test fun samples_areAudibleSine() {
        val raw = ByteBuffer.wrap(bytes()).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0
        var i = 44
        while (i < raw.limit()) {
            val v = Math.abs(raw.getShort(i).toInt())
            if (v > peak) peak = v
            i += 2
        }
        assertTrue("peak $peak too quiet", peak > 8000)
        assertTrue("peak $peak clipped", peak <= 32767)
    }
}
