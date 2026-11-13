// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026-present Open Handheld Collective
//   https://github.com/OpenHandheldCollective

package org.openhandheldcollective.openabl.installer.update

import org.openhandheldcollective.openabl.installer.abl.Hash
import org.openhandheldcollective.openabl.installer.abl.Image
import org.openhandheldcollective.openabl.installer.abl.Versions
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// An OpenABL release as far as this device's chip goes: its image, and the checksum that
// goes with it, either the release's SHA256SUMS or the .sha256 beside the image.
// [elf] is null when the release has no image for the chip.
class Release(val version: String, val elf: Asset?, val sums: Asset?, val elfSum: Asset?) {
    val usable get() = elf != null && (sums != null || elfSum != null)

    class Asset(val name: String, val url: String, val digest: String?, val size: Long = 0)
}

// A newer installer APK, opened in the browser.
class AppRelease(val version: String, val page: String)

class UpdateException(message: String) : Exception(message)

object Update {
    private const val REPO = "https://api.github.com/repos/OpenHandheldCollective/OpenABL"
    private const val LATEST_APP = "https://api.github.com/repos/OpenHandheldCollective/OpenABL-Android-Installer/releases/latest"
    private const val WEB = "https://github.com/OpenHandheldCollective/OpenABL"
    private const val WEB_APP = "https://github.com/OpenHandheldCollective/OpenABL-Android-Installer"
    private val TAG = Regex("^v\\d+(\\.\\d+){1,2}$")
    private const val MAX_SUMS = 64 * 1024
    private const val MAX_IMAGE = 4L * 1024 * 1024

    // The latest OpenABL release for [chip].
    // The release page is tried first, as github.com isn't rate limited the way the API is.
    fun latest(chip: String?): Release {
        val tag = webTag(WEB)
        if (tag != null) return webRelease(tag, chip)
        return parse(JSONObject(String(fetch("$REPO/releases/latest", 1 shl 20))), chip)
    }

    // The release for [tag] as far as its fixed file names go.
    internal fun webRelease(tag: String, chip: String?): Release {
        val base = "$WEB/releases/download/$tag"
        fun asset(name: String) = Release.Asset(name, "$base/$name", null)
        val elf = chip?.let { asset("abl_signed-$it.elf") }
        val elfSum = chip?.let { asset("abl_signed-$it.elf.sha256") }
        return Release(tag.removePrefix("v"), elf, asset("SHA256SUMS"), elfSum)
    }

    // The tag [repo]'s latest release redirects to, or null if that can't be told.
    private fun webTag(repo: String): String? = runCatching {
        val tag = resolver("$repo/releases/latest")?.substringAfterLast("/releases/tag/", "")
        tag?.takeIf { TAG.matches(it) }
    }.getOrNull()

    // The latest installer release if newer than [app].
    fun latestApp(app: String): AppRelease? {
        val tag = webTag(WEB_APP)
            ?: return runCatching { parseApp(JSONObject(String(fetch(LATEST_APP, 1 shl 20))), app) }.getOrNull()
        if (Versions.compare(tag, app) <= 0) return null
        val apk = "$WEB_APP/releases/download/$tag/openabl-installer-$tag.apk"
        if (!runCatching { exists(apk) }.getOrDefault(false)) return null
        return AppRelease(tag.removePrefix("v"), "$WEB_APP/releases/tag/$tag")
    }

    fun parseApp(json: JSONObject, have: String): AppRelease? {
        val tag = json.getString("tag_name")
        if (Versions.compare(tag, have) <= 0) return null
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name") == "openabl-installer-$tag.apk" } ?: return null
        return AppRelease(tag.removePrefix("v"), json.optString("html_url").ifEmpty { apk.getString("browser_download_url") })
    }

    // Test hook: how a URL is opened.
    internal var opener: (String) -> InputStream = ::openUrl

    // Test hook: the Location a URL redirects to, or null if it doesn't redirect.
    internal var resolver: (String) -> String? = ::redirectOf

    // Test hook: whether a URL exists, by a HEAD request.
    internal var exists: (String) -> Boolean = ::headExists

    private fun assets(json: JSONObject) = json.getJSONArray("assets").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Release.Asset(
                it.getString("name"), it.getString("browser_download_url"),
                it.optString("digest").takeIf { d -> d.isNotEmpty() }, it.optLong("size"),
            )
        }
    }

    fun parse(json: JSONObject, chip: String?): Release {
        val list = assets(json)
        val elf = chip?.let { c -> list.firstOrNull { it.name == "abl_signed-$c.elf" } }
        val elfSum = chip?.let { c -> list.firstOrNull { it.name == "abl_signed-$c.elf.sha256" } }
        return Release(json.getString("tag_name").removePrefix("v"), elf, list.firstOrNull { it.name == "SHA256SUMS" }, elfSum)
    }

    // The checksum the release publishes for [elf], from its SHA256SUMS or else its .sha256.
    private fun wanted(release: Release, elf: Release.Asset): String {
        val sums = release.sums
        val sum = release.elfSum
        if (sums != null) {
            // Older releases only publish the .sha256 beside each image.
            val listed = runCatching { parseSums(String(fetch(sums.url, MAX_SUMS)))[elf.name] }
            listed.getOrNull()?.let { return it }
            if (sum == null) {
                throw listed.exceptionOrNull() ?: UpdateException("SHA256SUMS doesn't list ${elf.name}.")
            }
        }
        if (sum == null) throw UpdateException("This release has no checksum for ${elf.name}.")
        val line = String(fetch(sum.url, MAX_SUMS)).lineSequence().firstOrNull().orEmpty().trim()
        val m = Regex("^([0-9a-fA-F]{64})(?:\\s+\\*?(\\S+))?$").find(line)
            ?: throw UpdateException("${sum.name} isn't a checksum.")
        if (m.groupValues[2].isNotEmpty() && m.groupValues[2].substringAfterLast('/') != elf.name) {
            throw UpdateException("${sum.name} is for a different file.")
        }
        return m.groupValues[1].lowercase()
    }

    // Downloads this chip's image into [dest] (the .elf and its .sha256). Nothing is kept
    // unless the image matches the release's checksum.
    fun download(release: Release, dest: File) {
        val elf = release.elf
        if (elf == null || !release.usable) throw UpdateException("This release has no image for this device.")
        val want = try { wanted(release, elf) } catch (e: UpdateException) { throw noImage(e) }
        val api = elf.digest?.removePrefix("sha256:")
        if (api != null && api != want) throw UpdateException("GitHub and the release's checksum disagree about ${elf.name}.")

        val out = File(dest.path + ".part")
        out.deleteRecursively()
        out.mkdirs()
        try {
            val f = File(out, elf.name)
            val s = try { opener(elf.url) } catch (e: UpdateException) { throw noImage(e) }
            s.use { f.outputStream().use { o -> copyLimited(s, o, MAX_IMAGE) } }
            if (Hash.sha256(f) != want) {
                throw UpdateException("${elf.name} doesn't match the release's checksum, so it was not used.")
            }
            File(out, "${elf.name}.sha256").writeText("$want  ${elf.name}\n")
            dest.deleteRecursively()
            if (!out.renameTo(dest)) throw UpdateException("Couldn't save the download.")
        } finally {
            out.deleteRecursively()
        }
    }

    private fun noImage(e: UpdateException) =
        if (e.message == "The server answered 404.") UpdateException("This release has no image for this device.") else e

    // The hashes of every image of every OpenABL release, so slots can be recognised.
    // [have] are releases already known, which are skipped. Releases that fail are left out.
    fun knownImages(have: Set<String>): List<Image> {
        val releases = org.json.JSONArray(String(fetch("$REPO/releases?per_page=100", 4 shl 20)))
        val out = ArrayList<Image>()
        for (n in 0 until releases.length()) {
            val json = releases.getJSONObject(n)
            val tag = json.getString("tag_name")
            if (!Regex("^v\\d+(\\.\\d+){1,2}$").matches(tag) || json.optBoolean("draft")) continue
            val version = tag.removePrefix("v")
            if (version in have) continue
            runCatching { out += releaseImages(version, assets(json)) }
        }
        return out
    }

    internal fun releaseImages(version: String, list: List<Release.Asset>): List<Image> {
        val sums = list.firstOrNull { it.name == "SHA256SUMS" }?.let { parseSums(String(fetch(it.url, MAX_SUMS))) }
        return list.mapNotNull { a ->
            val chip = Regex("abl_signed-([A-Z0-9]+)\\.elf").matchEntire(a.name)?.groupValues?.get(1) ?: return@mapNotNull null
            if (a.size <= 0) return@mapNotNull null
            val sha = sums?.get(a.name) ?: list.firstOrNull { it.name == a.name + ".sha256" }?.let {
                Regex("^[0-9a-fA-F]{64}").find(String(fetch(it.url, MAX_SUMS)).trim())?.value?.lowercase()
            } ?: return@mapNotNull null
            Image(version, chip, sha, a.size)
        }
    }

    fun parseSums(text: String): Map<String, String> = text.lineSequence().mapNotNull {
        val m = Regex("^([0-9a-f]{64}) [ *](\\S+)$").find(it.trim()) ?: return@mapNotNull null
        m.groupValues[2] to m.groupValues[1]
    }.toMap()

    private fun copyLimited(i: InputStream, o: java.io.OutputStream, limit: Long) {
        val buf = ByteArray(65536)
        var total = 0L
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw UpdateException("The download is too large.")
            o.write(buf, 0, n)
        }
    }

    private fun openUrl(url: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "OpenABL-Installer")
        val code = c.responseCode
        if (code != 200) {
            if ((code == 403 || code == 429) && c.url.host == "api.github.com") {
                throw UpdateException(limitMessage(c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()))
            }
            throw UpdateException("The server answered $code.")
        }
        return c.inputStream
    }

    // What to tell the user when GitHub limits the API; [reset] is epoch seconds.
    internal fun limitMessage(reset: Long?, zone: TimeZone = TimeZone.getDefault()): String {
        val base = "GitHub is limiting requests from this network."
        if (reset == null) return "$base Try again in an hour."
        val f = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = zone }
        return "$base Try again after ${f.format(Date(reset * 1000))}."
    }

    private fun head(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "OpenABL-Installer")
        return c
    }

    private fun redirectOf(url: String): String? {
        val c = head(url)
        try {
            return if (c.responseCode in 300..399) c.getHeaderField("Location") else null
        } finally {
            c.disconnect()
        }
    }

    private fun headExists(url: String): Boolean {
        val c = head(url)
        c.requestMethod = "HEAD"
        try {
            return c.responseCode.let { it == 200 || it == 302 }
        } finally {
            c.disconnect()
        }
    }

    private fun fetch(url: String, max: Int): ByteArray = opener(url).use {
        val out = java.io.ByteArrayOutputStream()
        copyLimited(it, out, max.toLong())
        out.toByteArray()
    }
}
