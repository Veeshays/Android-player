package com.veeshays.pulse

import android.net.Uri
import com.veeshays.pulse.playback.TONE_MEDIA_ID
import com.veeshays.pulse.playback.resolveIdentityToUri
import com.veeshays.pulse.playback.toneResourceUri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Session-resolution contract: requested identity → playable URI.
 * Custom labels are never playable; canonical URIs pass through untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResolverTest {

    @Test fun toneIdentity_resolvesToSuppliedFile() {
        val uri = resolveIdentityToUri(TONE_MEDIA_ID) { Uri.parse("file:///cache/probe_tone.wav") }
        assertEquals("file:///cache/probe_tone.wav", uri.toString())
    }

    @Test fun toneResourceUri_isPackageScoped() {
        val uri = toneResourceUri("com.veeshays.pulse.debug")
        assertEquals("android.resource://com.veeshays.pulse.debug/raw/probe_tone", uri)
        // And the resolver passes resource URIs straight through.
        assertEquals(uri, resolveIdentityToUri(uri) { null }.toString())
    }

    @Test fun contentIdentity_resolvesToItself() {
        val id = "content://media/external/audio/media/8890"
        assertEquals(id, resolveIdentityToUri(id) { null }.toString())
    }

    @Test fun fileIdentity_resolvesToItself() {
        val id = "file:///cache/probe_tone.wav"
        assertEquals(id, resolveIdentityToUri(id) { null }.toString())
    }

    @Test fun bareNumericId_neverResolves() {
        // The 8890 class of bug: a database ID is not a source.
        assertNull(resolveIdentityToUri("8890") { null })
    }

    @Test fun displayTitle_neverResolves() {
        assertNull(resolveIdentityToUri("My Song.mp3") { null })
    }

    @Test fun unknownCustomScheme_neverResolves() {
        assertNull(resolveIdentityToUri("pupplay://something-else") { null })
    }

    @Test fun toneSupplierFailure_resolvesToNull() {
        assertNull(resolveIdentityToUri(TONE_MEDIA_ID) { throw RuntimeException("nope") })
    }
}
