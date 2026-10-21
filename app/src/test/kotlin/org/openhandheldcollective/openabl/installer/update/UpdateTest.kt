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
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
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
}
