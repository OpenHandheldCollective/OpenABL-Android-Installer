// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026-present Open Handheld Collective
//   https://github.com/OpenHandheldCollective

package org.openhandheldcollective.openabl.installer.update

import org.openhandheldcollective.openabl.installer.abl.Image
import org.openhandheldcollective.openabl.installer.abl.Known
import org.openhandheldcollective.openabl.installer.abl.Versions
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    @After fun resetHooks() {
        Update.resolver = { null }
        Update.exists = { false }
        Update.opener = { throw IllegalStateException("network") }
    }

    private fun release(tag: String, assets: List<String>) = JSONObject(
        """{"tag_name":"$tag","assets":[${assets.joinToString(",") { """{"name":"$it","browser_download_url":"https://x/$it"}""" }}]}""",
    )

    @Test fun versionsCompareByNumber() {
        assertTrue(Versions.compare("v1.10", "1.9") > 0)
        assertEquals(0, Versions.compare("v1.0", "1.0"))
        assertTrue(Versions.compare("1.0", "1.1") < 0)
    }

    @Test fun aReleaseWithSumsIsUsableForItsChip() {
        val r = Update.parse(release("v1.1", listOf("abl_signed-SM8550.elf", "SHA256SUMS")), "SM8550")
        assertEquals("1.1", r.version)
        assertTrue(r.usable)
        assertNotNull(r.sums)
    }

    @Test fun aReleaseWithoutSumsUsesThePerChipChecksum() {
        val names = listOf("abl_signed-SM8550.elf", "abl_signed-SM8550.elf.sha256", "abl_signed-SM8250.elf")
        val r = Update.parse(release("v1.1", names), "SM8550")
        assertNull(r.sums)
        assertTrue(r.usable)
        assertEquals("abl_signed-SM8550.elf", r.elf!!.name)
    }

    @Test fun aReleaseWithoutThisChipsImageIsNotUsable() {
        val names = listOf("abl_signed-SM8550.elf", "abl_signed-SM8550.elf.sha256", "abl_signed-SM8250.elf")
        assertTrue(!Update.parse(release("v1.1", names), "SM8250").usable)
        assertTrue(!Update.parse(release("v1.1", names), "SM6115").usable)
        assertTrue(!Update.parse(release("v1.1", names), null).usable)
    }

    private fun withFake(files: Map<String, ByteArray>, body: (File) -> Unit) {
        val dir = Files.createTempDirectory("upd").toRealPath().toFile()
        Update.opener = { url -> (files[url.substringAfterLast('/')] ?: throw UpdateException("The server answered 404.")).inputStream() }
        try { body(dir) } finally {
            Update.opener = { throw IllegalStateException("network") }
            dir.deleteRecursively()
        }
    }

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun perChipImageIsVerifiedAndKept() {
        val img = "image".toByteArray()
        val files = mapOf("abl_signed-SM8550.elf" to img, "abl_signed-SM8550.elf.sha256" to "${sha(img)}  abl_signed-SM8550.elf\n".toByteArray())
        withFake(files) { dir ->
            val r = Update.parse(release("v1.1", files.keys.toList()), "SM8550")
            val dest = File(dir, "1.1")
            Update.download(r, dest)
            assertEquals("image", File(dest, "abl_signed-SM8550.elf").readText())
            assertEquals(sha(img), File(dest, "abl_signed-SM8550.elf.sha256").readText().trim().substringBefore(' '))
            assertTrue(!File(dest.path + ".part").exists())
        }
    }

    @Test fun perChipMismatchIsRefusedAndNothingKept() {
        val files = mapOf("abl_signed-SM8550.elf" to "image".toByteArray(), "abl_signed-SM8550.elf.sha256" to "${"0".repeat(64)}  abl_signed-SM8550.elf\n".toByteArray())
        withFake(files) { dir ->
            val r = Update.parse(release("v1.0", files.keys.toList()), "SM8550")
            val dest = File(dir, "1.0")
            val e = runCatching { Update.download(r, dest) }.exceptionOrNull()
            assertTrue(e is UpdateException)
            assertTrue(e!!.message!!.contains("doesn't match"))
            assertTrue(!dest.exists())
            assertTrue(!File(dest.path + ".part").exists())
        }
    }

    @Test fun sumsAreUsedInPreferenceToThePerChipFile() {
        val img = "image".toByteArray()
        val good = "${sha(img)}  abl_signed-SM8550.elf\n${"1".repeat(64)}  abl_signed-SM8250.elf\n"
        val files = mapOf("abl_signed-SM8550.elf" to img, "SHA256SUMS" to good.toByteArray(), "abl_signed-SM8550.elf.sha256" to "${"0".repeat(64)}\n".toByteArray())
        withFake(files) { dir ->
            Update.download(Update.parse(release("v1.1", files.keys.toList()), "SM8550"), File(dir, "1.1"))
            assertEquals("image", File(dir, "1.1/abl_signed-SM8550.elf").readText())
        }
        withFake(files + ("SHA256SUMS" to "${"2".repeat(64)}  abl_signed-SM8550.elf\n".toByteArray())) { dir ->
            val e = runCatching { Update.download(Update.parse(release("v1.1", files.keys.toList()), "SM8550"), File(dir, "1.1")) }.exceptionOrNull()
            assertTrue(e is UpdateException)
            assertTrue(!File(dir, "1.1").exists())
        }
    }

    @Test fun downloadWithNoImageIsRefused() {
        withFake(emptyMap()) { dir ->
            val e = runCatching { Update.download(Update.parse(release("v1.1", listOf("abl_signed-SM8250.elf")), "SM8550"), File(dir, "1.1")) }.exceptionOrNull()
            assertTrue(e is UpdateException)
        }
    }

    @Test fun releaseHashesComeFromSumsOrPerImageFiles() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        withFake(mapOf("SHA256SUMS" to "$a  abl_signed-SM8550.elf\n".toByteArray(), "abl_signed-SM8250.elf.sha256" to "$b  abl_signed-SM8250.elf\n".toByteArray())) {
            fun asset(n: String) = Release.Asset(n, "https://x/$n", null, 100)
            val withSums = Update.releaseImages("1.1", listOf(asset("abl_signed-SM8550.elf"), asset("SHA256SUMS")))
            assertEquals(listOf(Image("1.1", "SM8550", a, 100)), withSums)
            val perImage = Update.releaseImages("1.0", listOf(asset("abl_signed-SM8250.elf"), asset("abl_signed-SM8250.elf.sha256")))
            assertEquals(listOf(Image("1.0", "SM8250", b, 100)), perImage)
        }
    }

    @Test fun sumsAreParsed() {
        val m = Update.parseSums("${"a".repeat(64)}  openabl-v1.1.tar.gz\n${"b".repeat(64)} *abl_signed-SM8550.elf\nnot a line\n")
        assertEquals("a".repeat(64), m["openabl-v1.1.tar.gz"])
        assertEquals("b".repeat(64), m["abl_signed-SM8550.elf"])
        assertEquals(2, m.size)
    }

    @Test fun knownImagesAreParsedAndFound() {
        val k = Known.parse("# c\n1.0 SM8550 ${"a".repeat(64)} 532480\n1.0 SM8250 ${"b".repeat(64)} 258048\n")
        assertEquals(sortedSetOf(258048L, 532480L), k.lengths)
        assertEquals("SM8550", k.find("a".repeat(64), 532480)!!.chip)
        assertNull(k.find("a".repeat(64), 258048))
    }

    @Test fun newerInstallerOnlyWithItsApk() {
        assertNull(Update.parseApp(release("v1.1", listOf("SHA256SUMS")), "1.0"))
        assertNull(Update.parseApp(release("v1.0", listOf("openabl-installer-v1.0.apk")), "1.0"))
        assertEquals("1.1", Update.parseApp(release("v1.1", listOf("openabl-installer-v1.1.apk")), "1.0")!!.version)
    }

    private val web = "https://github.com/OpenHandheldCollective/OpenABL/releases/download/v1.2"

    @Test fun webRouteBuildsTheFixedUrls() {
        Update.resolver = { "https://github.com/OpenHandheldCollective/OpenABL/releases/tag/v1.2" }
        val r = Update.latest("SM8650")
        assertEquals("1.2", r.version)
        assertEquals("abl_signed-SM8650.elf", r.elf!!.name)
        assertEquals("$web/abl_signed-SM8650.elf", r.elf!!.url)
        assertEquals("$web/abl_signed-SM8650.elf.sha256", r.elfSum!!.url)
        assertEquals("$web/SHA256SUMS", r.sums!!.url)
        assertTrue(r.usable)
        assertNull(Update.latest(null).elf)
    }

    @Test fun latestFallsBackToTheApiWithoutARedirect() {
        val json = release("v1.1", listOf("abl_signed-SM8650.elf", "SHA256SUMS")).toString()
        Update.opener = { url ->
            assertTrue(url.startsWith("https://api.github.com/"))
            json.byteInputStream()
        }
        for (loc in listOf<String?>(null, "https://github.com/x/y/releases/tag/nightly")) {
            Update.resolver = { loc }
            val r = Update.latest("SM8650")
            assertEquals("1.1", r.version)
            assertEquals("https://x/abl_signed-SM8650.elf", r.elf!!.url)
        }
        Update.resolver = { throw java.io.IOException("offline") }
        assertEquals("1.1", Update.latest("SM8650").version)
    }

    @Test fun onlyThePerImageChecksumIsEnoughWhenSumsAreMissing() {
        val img = "image".toByteArray()
        val files = mapOf("abl_signed-SM8650.elf" to img, "abl_signed-SM8650.elf.sha256" to "${sha(img)}  abl_signed-SM8650.elf\n".toByteArray())
        withFake(files) { dir ->
            Update.resolver = { "https://github.com/OpenHandheldCollective/OpenABL/releases/tag/v1.2" }
            val dest = File(dir, "1.2")
            Update.download(Update.latest("SM8650"), dest)
            assertEquals("image", File(dest, "abl_signed-SM8650.elf").readText())
        }
    }

    @Test fun sumsThatDontListTheImageFallBackToThePerImageChecksum() {
        val img = "image".toByteArray()
        val files = mapOf(
            "abl_signed-SM8650.elf" to img,
            "SHA256SUMS" to "${"1".repeat(64)}  abl_signed-SM8250.elf\n".toByteArray(),
            "abl_signed-SM8650.elf.sha256" to "${sha(img)}\n".toByteArray(),
        )
        withFake(files) { dir ->
            Update.download(Update.webRelease("v1.2", "SM8650"), File(dir, "1.2"))
            assertTrue(File(dir, "1.2/abl_signed-SM8650.elf").exists())
        }
    }

    @Test fun noChecksumAtAllFailsAsBefore() {
        withFake(mapOf("abl_signed-SM8650.elf" to "image".toByteArray())) { dir ->
            val e = runCatching { Update.download(Update.webRelease("v1.2", "SM8650"), File(dir, "1.2")) }.exceptionOrNull()
            assertTrue(e is UpdateException)
            assertTrue(!File(dir, "1.2").exists())
        }
    }

    @Test fun aMissingImageIsReportedAsNoImageForThisDevice() {
        withFake(emptyMap()) { dir ->
            val e = runCatching { Update.download(Update.webRelease("v1.2", "SM8650"), File(dir, "1.2")) }.exceptionOrNull()
            assertEquals("This release has no image for this device.", e!!.message)
        }
        val sum = "${"1".repeat(64)}  abl_signed-SM8650.elf\n"
        withFake(mapOf("SHA256SUMS" to sum.toByteArray())) { dir ->
            val e = runCatching { Update.download(Update.webRelease("v1.2", "SM8650"), File(dir, "1.2")) }.exceptionOrNull()
            assertEquals("This release has no image for this device.", e!!.message)
        }
    }

    @Test fun rateLimitMessageNamesTheResetTimeOrAnHour() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        assertEquals("GitHub is limiting requests from this network. Try again after 22:13.", Update.limitMessage(1_700_000_000L, utc))
        assertEquals("GitHub is limiting requests from this network. Try again in an hour.", Update.limitMessage(null))
    }

    @Test fun latestAppUsesTheReleasePage() {
        val base = "https://github.com/OpenHandheldCollective/OpenABL-Android-Installer"
        val asked = ArrayList<String>()
        Update.resolver = { "$base/releases/tag/v1.3" }
        Update.exists = { asked += it; true }
        val r = Update.latestApp("1.2")!!
        assertEquals("1.3", r.version)
        assertEquals("$base/releases/tag/v1.3", r.page)
        assertEquals(listOf("$base/releases/download/v1.3/openabl-installer-v1.3.apk"), asked)
        assertNull(Update.latestApp("1.3"))
        Update.exists = { false }
        assertNull(Update.latestApp("1.2"))
    }
}
