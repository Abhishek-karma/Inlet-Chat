package com.assistant.app.data.update

/**
 * Parses and compares semantic version strings (e.g., "1.0.0", "v1.2.3").
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (preRelease == null && other.preRelease != null) return 1
        if (preRelease != null && other.preRelease == null) return -1
        if (preRelease != null && other.preRelease != null) return preRelease.compareTo(other.preRelease)
        return 0
    }

    companion object {
        fun parse(version: String): SemanticVersion? {
            val trimmed = version.trim().removePrefix("v").removePrefix("V")
            if (trimmed.isEmpty()) return null
            val hyphenSplit = trimmed.split('-', limit = 2)
            val versionNumbers = hyphenSplit[0].split('.')
            val major = versionNumbers.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = versionNumbers.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = versionNumbers.getOrNull(2)?.toIntOrNull() ?: 0
            val preRelease = hyphenSplit.getOrNull(1)?.takeIf { it.isNotBlank() }
            return SemanticVersion(major, minor, patch, preRelease)
        }

        fun isNewer(latest: String, current: String): Boolean {
            val latestParsed = parse(latest) ?: return false
            val currentParsed = parse(current) ?: return false
            return latestParsed > currentParsed
        }
    }
}
