package com.example.security

import com.example.BuildConfig

/**
 * Access policy guarding the internal Developer Panel and developer-only tools.
 *
 * Until trusted server-verified authentication and role authorization
 * (such as Firebase Auth with verified role claims) are implemented,
 * developer tools remain strictly disabled in release/user builds.
 */
object DeveloperAccessPolicy {
    fun isDeveloperToolsEnabled(isDebugBuild: Boolean = BuildConfig.DEBUG): Boolean {
        return isDebugBuild
    }
}
