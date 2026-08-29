package com.linger.app

/**
 * Toggle dev-only features. Defaults to true on debug builds, false on release.
 * Override DEV_OVERRIDE to force either mode regardless of build type.
 */
object DevConfig {
    private val DEV_OVERRIDE: Boolean? = false  // set to true/false to force, or null for auto

    val enabled: Boolean get() = DEV_OVERRIDE ?: BuildConfig.DEBUG
}
