package com.dragonemperor9480.yaedock.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A version as releases are tagged: `1.2.3`, or `1.2.3-beta.1` for a
 * pre-release. Ordered as semantic versioning orders them: a pre-release
 * comes before its release, and its parts compare as numbers when they are.
 */
data class Version(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch).let { if (it != 0) return it }
        if (pre.isEmpty() || other.pre.isEmpty()) return other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
        for ((a, b) in pre.zip(other.pre)) {
            val (x, y) = a.toIntOrNull() to b.toIntOrNull()
            val order = when {
                x != null && y != null -> x.compareTo(y)
                // Numbers come before words.
                x != null -> -1
                y != null -> 1
                else -> a.compareTo(b)
            }
            if (order != 0) return order
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun toString() = "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private val PATTERN = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+.*)?""")

        /** [text] as a version, such as a tag `v0.2.0` or a version name `0.2.0`; null if it is not one. */
        fun parse(text: String): Version? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return runCatching {
                Version(major.toInt(), minor.toInt(), patch.toInt(), pre.split('.').filter { it.isNotEmpty() })
            }.getOrNull()
        }
    }
}

/** A file of a release on GitHub. */
@Serializable
data class Asset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

/** A published release: its version, notes and files. */
data class Release(val version: Version, val notes: String, val page: String, val assets: List<Asset>) {
    fun asset(name: String): Asset? = assets.find { it.name == name }

    /** The APK for the first of [abis] (the phone's, best first) that this release has. */
    fun apk(abis: List<String>): Asset? = abis.firstNotNullOfOrNull { asset("yaedock-$version-$it.apk") }
}

object Releases {
    /** Every release, newest first. Pre-releases count as updates too. */
    const val GITHUB = "https://api.github.com/repos/DragonEmperor9480/yaedock/releases?per_page=20"

    /** `sha256sum` output for every file of a release. */
    const val SUMS = "SHA256SUMS"

    @Serializable
    private class Listed(
        @SerialName("tag_name") val tag: String,
        val draft: Boolean = false,
        val body: String? = null,
        @SerialName("html_url") val page: String = "",
        val assets: List<Asset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The newest published release in GitHub's list of releases [text], or null when there is none. */
    fun newest(text: String): Release? =
        json.decodeFromString<List<Listed>>(text)
            .filter { !it.draft }
            .mapNotNull { listed ->
                Release(Version.parse(listed.tag) ?: return@mapNotNull null, listed.body.orEmpty().trim(), listed.page, listed.assets)
            }
            .maxByOrNull { it.version }

    /** The SHA-256 that `sha256sum` output [sums] gives for the file [name], in lowercase hex. */
    fun checksum(sums: String, name: String): String? = sums.lineSequence().firstNotNullOfOrNull { line ->
        val hash = line.substringBefore(' ').trim()
        // `sha256sum` marks binary mode with a `*` before the name.
        val file = line.substringAfter(' ', "").trimStart().removePrefix("*")
        hash.lowercase().takeIf { file == name && it.length == 64 }
    }
}
