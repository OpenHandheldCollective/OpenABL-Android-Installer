// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026-present Open Handheld Collective
//   https://github.com/OpenHandheldCollective

package org.openhandheldcollective.openabl.installer.abl

import android.content.Context
import java.io.File
import java.security.MessageDigest

data class Image(val version: String, val chip: String, val sha256: String, val bytes: Long)

// Release images the app knows by hash: assets/known-images.txt and what releases published.
class Known(val images: List<Image>) {
    val lengths get() = images.map { it.bytes }.toSortedSet()

    fun find(sha256: String, bytes: Long) = images.firstOrNull { it.sha256 == sha256 && it.bytes == bytes }

    companion object {
        fun parse(text: String) = Known(
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map {
                val f = it.split(" ")
                Image(f[0], f[1], f[2], f[3].toLong())
            }.toList(),
        )
    }
}

// An image ready to hand to a root script.
class Ready(val version: String, val file: File, val sha256: String, val bytes: Long)

object Hash {
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(65536)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

// The newest verified image for the chip, kept in app storage from the last time it was
// downloaded, and the hashes of the releases seen so far.
class Images(private val context: Context) {
    private val bundled: List<Image> = Known.parse(context.assets.open("known-images.txt").bufferedReader().use { it.readText() }).images
    private val seen get() = File(context.filesDir, "known-remote.txt")
    private val downloads get() = File(context.getExternalFilesDir(null), "releases").apply { mkdirs() }

    var known: Known = load()
        private set

    private fun load(): Known {
        val cached = runCatching { Known.parse(seen.readText()).images }.getOrDefault(emptyList())
        return Known((bundled + cached).distinctBy { Triple(it.sha256, it.bytes, it.chip) })
    }

    // Releases whose hashes are saved.
    fun seenVersions(): Set<String> = runCatching { Known.parse(seen.readText()).images.map { it.version }.toSet() }.getOrDefault(emptySet())

    fun remember(images: List<Image>) {
        if (images.isEmpty()) return
        val old = runCatching { Known.parse(seen.readText()).images }.getOrDefault(emptyList())
        seen.writeText("# version chipset sha256 bytes\n" + (old + images).joinToString("\n") { "${it.version} ${it.chip} ${it.sha256} ${it.bytes}" } + "\n")
        known = load()
    }

    fun downloadedDir(version: String) = File(downloads, version)

    // Drops downloads of other versions once [version] is saved.
    fun keepOnly(version: String) {
        downloads.listFiles().orEmpty().filter { it.name != version }.forEach { it.deleteRecursively() }
    }

    // The newest saved image for the chip, if it still matches the hash saved with it.
    fun newest(chip: String): Ready? {
        val name = "abl_signed-$chip.elf"
        return downloads.listFiles().orEmpty()
            .filter { File(it, name).isFile && File(it, "$name.sha256").isFile }
            .sortedWith { a, b -> Versions.compare(b.name, a.name) }
            .firstNotNullOfOrNull { dir ->
                val file = File(dir, name)
                val want = File(dir, "$name.sha256").readText().trim().substringBefore(' ')
                val sha = Hash.sha256(file)
                if (sha == want) Ready(dir.name, file, sha, file.length()) else null
            }
    }
}

object Versions {
    fun parse(v: String) = v.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }

    fun compare(a: String, b: String): Int {
        val x = parse(a)
        val y = parse(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
