package com.veeshays.pulse

import com.veeshays.pulse.core.Kind
import com.veeshays.pulse.core.classify
import com.veeshays.pulse.core.message
import org.junit.Assert.*
import org.junit.Test

/**
 * Playback failures must surface concise user copy — never silence, never a
 * stack trace. These pin the classification contract behind the error channel.
 */
class ErrorsTest {

    @Test fun missingFile_mapsToGoneCopy() {
        val k = classify(RuntimeException("ENOENT (No such file or directory)"))
        assertEquals(Kind.MISSING_FILE, k)
        assertTrue(message(k).contains("no longer available"))
    }

    @Test fun permission_mapsToSettingsCopy() {
        val k = classify(SecurityException("Permission Denial: reading media"))
        assertEquals(Kind.PERMISSION, k)
        assertTrue(message(k).contains("permission"))
    }

    @Test fun decoder_mapsToCodecCopy() {
        val k = classify(RuntimeException("Decoder init failed: OMX.qcom.audio.decoder"))
        assertEquals(Kind.DECODER, k)
        assertTrue(message(k).contains("decode"))
    }

    @Test fun unsupported_mapsToSkippedCopy() {
        val k = classify(RuntimeException("Unsupported mime type for extractor"))
        assertEquals(Kind.UNSUPPORTED, k)
        assertTrue(message(k).contains("Unsupported"))
    }

    @Test fun generic_mapsToSafeFallback() {
        val k = classify(RuntimeException("Source error"))
        assertEquals(Kind.PLAYBACK, k)
        assertFalse(message(k).isBlank())
        assertFalse(message(k).contains("Exception"))
    }

    @Test fun noCopy_leaksInternals() {
        for (k in Kind.values()) {
            val m = message(k)
            assertFalse(m.contains("at com.veeshays"))
            assertFalse(m.contains("Exception"))
        }
    }
}
