package com.veeshays.pulse

import com.veeshays.pulse.playback.resolveIdentityToUri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Session-resolution contract (rebuilt foundation): requested identity →
 * playable URI. Custom labels are never playable; canonical URIs pass
 * through untouched. There is no probe-tone branch by design — the
 * diagnostic tone lives outside ExoPlayer (TonePlayer) and must never
 * appear in the playback path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResolverTest {

    @Test fun resourceUri_passesThrough() {
        val uri = "android.resource://com.veeshays.pulse.debug/raw/probe_tone"
        assertEquals(uri, resolveIdentityToUri(uri).toString())
    }

    @Test fun contentIdentity_resolvesToItself() {
        val id = "content://media/external/audio/media/8890"
        assertEquals(id, resolveIdentityToUri(id).toString())
    }

    @Test fun fileIdentity_resolvesToItself() {
        val id = "file:///cache/probe_tone.wav"
        assertEquals(id, resolveIdentityToUri(id).toString())
    }

    @Test fun bareNumericId_neverResolves() {
        // The 8890 class of bug: a database ID is not a source.
        assertNull(resolveIdentityToUri("8890"))
    }

    @Test fun displayTitle_neverResolves() {
        assertNull(resolveIdentityToUri("My Song.mp3"))
    }

    @Test fun unknownCustomScheme_neverResolves() {
        assertNull(resolveIdentityToUri("pupplay://something-else"))
    }

    @Test fun probeToneLabel_neverResolves() {
        // Rebuild gate: the old pupplay://probe-tone shortcut must NOT
        // resolve — proving playback never depends on it.
        assertNull(resolveIdentityToUri("pupplay://probe-tone"))
    }
}
