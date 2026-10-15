package com.example.entify_again.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityDiagnosticsTest {

    @Test
    fun securityStatus_dataClass_evaluatesCorrectly() {
        val safeStatus = SecurityDiagnostics.SecurityStatus(
            isDebuggable = false,
            isDebuggerAttached = false,
            isTestKeysBuild = false,
            hasRootArtifacts = false,
            isPotentiallyCompromised = false
        )
        assertFalse(safeStatus.isPotentiallyCompromised)

        val compromisedStatus = SecurityDiagnostics.SecurityStatus(
            isDebuggable = true,
            isDebuggerAttached = false,
            isTestKeysBuild = false,
            hasRootArtifacts = false,
            isPotentiallyCompromised = true
        )
        assertTrue(compromisedStatus.isPotentiallyCompromised)
    }
}
