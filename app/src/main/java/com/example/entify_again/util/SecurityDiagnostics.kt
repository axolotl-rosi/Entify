package com.example.entify_again.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import java.io.File

/**
 * Diagnostic helper for collecting non-intrusive runtime security signals.
 *
 * Per THREAT_MODEL and SECURITY_HARDENING_HANDOFF guidelines:
 * - Signals are for warning and diagnostic posture, NEVER for silent account locks.
 * - Explicitly acknowledges that root and hooking detections can be bypassed on hostile devices.
 */
object SecurityDiagnostics {

    data class SecurityStatus(
        val isDebuggable: Boolean,
        val isDebuggerAttached: Boolean,
        val isTestKeysBuild: Boolean,
        val hasRootArtifacts: Boolean,
        val isPotentiallyCompromised: Boolean
    )

    private val KNOWN_ROOT_PATHS = listOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su"
    )

    fun checkStatus(context: Context): SecurityStatus {
        val appInfo = context.applicationInfo
        val isDebuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val isDebuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        val isTestKeysBuild = Build.TAGS?.contains("test-keys") == true
        val hasRootArtifacts = checkRootFiles()

        val isCompromised = isDebuggable || isDebuggerAttached || isTestKeysBuild || hasRootArtifacts

        return SecurityStatus(
            isDebuggable = isDebuggable,
            isDebuggerAttached = isDebuggerAttached,
            isTestKeysBuild = isTestKeysBuild,
            hasRootArtifacts = hasRootArtifacts,
            isPotentiallyCompromised = isCompromised
        )
    }

    private fun checkRootFiles(): Boolean {
        return runCatching {
            KNOWN_ROOT_PATHS.any { path -> File(path).exists() }
        }.getOrDefault(false)
    }
}
