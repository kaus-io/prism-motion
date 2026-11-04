package com.zxhhyj.kap.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.util.Properties

/** Playback defaults and debug-only overrides. Release apps never read the override file. */
public data class KtxDebugOptions(
    val ringSize: Int? = null,
    val initialFileUpload: Boolean = false,
    val residentFrames: Boolean = false,
    val narrowTeardown: Boolean = false,
    val fastCacheHit: Boolean = false,
    val sharedVsync: Boolean = false,
    val throttleState: Boolean = true,
    val measure: Boolean = false,
    val earlyCache: Boolean = false,
    val filterTest: Boolean = false,
    val filterAuto: Boolean = false,
    val nativeHue: Boolean = false,
    val filterSource: String = "",
    val holdFrameZero: Boolean = false,
    val heldFrame: Int = 0,
    val surfaceMode: String = "embedded",
    val forcePressed: Boolean = false,
    val filterNormal: Boolean = false,
    val surfaceSource: String = "",
    val legacyFrameUpload: Boolean = false,
) {
    public companion object {
        @Volatile private var cached: KtxDebugOptions? = null

        public fun load(context: Context): KtxDebugOptions = cached ?: synchronized(this) {
            cached ?: read(context).also { cached = it }
        }

        private fun read(context: Context): KtxDebugOptions {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
                return KtxDebugOptions()
            }
            val application = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            if (!ktxExperimentOverridesAllowed(
                    application.flags,
                    application.metaData?.getBoolean("com.zxhhyj.kap.DEBUG_EXPERIMENTS", true) ?: true,
                )) return KtxDebugOptions()
            val file = File(context.filesDir, "kap-experiments.properties")
            val properties = Properties()
            if (file.isFile) file.inputStream().use { properties.load(it) }
            fun flag(name: String, default: Boolean = false): Boolean =
                properties.getProperty(name, default.toString()).toBooleanStrict()
            val ring = properties.getProperty("ringSize")?.toInt()
            require(ring == null || ring in listOf(4, 8, 16, 32)) { "unsupported experimental ring" }
            return KtxDebugOptions(
                ringSize = ring,
                initialFileUpload = flag("initialFileUpload"),
                residentFrames = flag("residentFrames"),
                narrowTeardown = flag("narrowTeardown"),
                fastCacheHit = flag("fastCacheHit"),
                sharedVsync = flag("sharedVsync"),
                throttleState = flag("throttleState", default = true),
                measure = flag("measure"),
                earlyCache = flag("earlyCache"),
                filterTest = flag("filterTest"),
                filterAuto = flag("filterAuto"),
                nativeHue = flag("nativeHue"),
                filterSource = properties.getProperty("filterSource", ""),
                holdFrameZero = flag("holdFrameZero"),
                heldFrame = properties.getProperty("heldFrame", "0").toInt().also { require(it >= 0) },
                surfaceMode = properties.getProperty("surfaceMode", "embedded").also {
                    require(it in listOf("embedded", "behind", "top"))
                },
                forcePressed = flag("forcePressed"),
                filterNormal = flag("filterNormal"),
                surfaceSource = properties.getProperty("surfaceSource", ""),
                legacyFrameUpload = flag("legacyFrameUpload"),
            ).also { Log.i("KtxExperiment", "options=$it") }
        }
    }
}

internal fun ktxExperimentOverridesAllowed(applicationFlags: Int, manifestEnabled: Boolean): Boolean =
    applicationFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && manifestEnabled
