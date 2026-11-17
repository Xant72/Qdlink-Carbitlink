package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderRestartPolicyTest {

    @Test
    fun `a session that never rendered counts restarts against the pin`() {
        // The one case the ladder exists for: a component that cannot decode this type at all.
        assertTrue(
            DecoderRestartPolicy.countsTowardFailure(
                codecTypePinned = true, startFailed = false, renderedSinceLastStart = false, renderedThisSession = false
            )
        )
    }

    @Test
    fun `a stream that has already rendered is proven and never escalates`() {
        // The relaunch case: the surface swap zeroed the per-start timestamp, but the session
        // has decoded this stream - warm-up churn is not evidence the codec is broken.
        assertFalse(
            DecoderRestartPolicy.countsTowardFailure(
                codecTypePinned = true, startFailed = false, renderedSinceLastStart = false, renderedThisSession = true
            )
        )
    }

    @Test
    fun `a restart after a frame from the current start never counts`() {
        assertFalse(
            DecoderRestartPolicy.countsTowardFailure(
                codecTypePinned = true, startFailed = false, renderedSinceLastStart = true, renderedThisSession = true
            )
        )
    }

    @Test
    fun `an unpinned session never counts restarts`() {
        // Before the first successful start there is no codec type to hold anything against.
        assertFalse(
            DecoderRestartPolicy.countsTowardFailure(
                codecTypePinned = false, startFailed = false, renderedSinceLastStart = false, renderedThisSession = false
            )
        )
    }

    @Test
    fun `a component that never starts counts even before the first pin`() {
        // Measured on a Xiaomi in transcode mode: the hardware decoder throws in start() every time,
        // so no start ever succeeds to pin the type - these failures still have to escalate, or the
        // software fallback and the type flip are never reached.
        assertTrue(
            DecoderRestartPolicy.countsTowardFailure(
                codecTypePinned = false, startFailed = true, renderedSinceLastStart = false, renderedThisSession = false
            )
        )
    }
}
