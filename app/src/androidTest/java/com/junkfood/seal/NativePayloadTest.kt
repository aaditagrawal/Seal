package com.junkfood.seal

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the installed APK and the upstream wrapper's real extraction directory. */
@RunWith(AndroidJUnit4::class)
class NativePayloadTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun extractedLibrariesAndSymlinksMatchTheInstalledArchive() {
        YoutubeDL.init(context)
        FFmpeg.init(context)
        val root = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        val archive = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.zip.so")
        val abi =
            if (android.os.Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS.first()
            else Build.SUPPORTED_32_BIT_ABIS.first()
        var symlinks = 0
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                val extracted = File(root, entry.name)
                assertTrue(extracted.canonicalPath.startsWith(root.canonicalPath + File.separator))
                val expected = zip.getInputStream(entry).use { it.readBytes() }
                if (OsConstants.S_ISLNK(Os.lstat(extracted.path).st_mode)) {
                    assertEquals(String(expected, Charsets.UTF_8), Os.readlink(extracted.path))
                    symlinks++
                } else {
                    assertEquals(entry.name, sha256(expected), sha256(extracted.readBytes()))
                }
            }
        }
        assertTrue("Versioned FFmpeg dependencies must remain symlinks", symlinks > 0)
        var checkedOverlays = 0
        context.assets.open("licenses/libwebp/SHA256SUMS").bufferedReader().useLines { lines ->
            lines.filter { it.contains("  overlay/$abi/") }.forEach { line ->
                checkedOverlays++
                val library = line.substringAfterLast('/')
                assertEquals(library, line.take(64), sha256(File(root, "usr/lib/$library").readBytes()))
            }
        }
        assertEquals(if (android.os.Process.is64Bit()) 5 else 0, checkedOverlays)
        assertEquals(
            "Archive length must be saved after an application update re-extracts FFmpeg",
            archive.length().toString(),
            context.getSharedPreferences("youtubedl-android", 0).getString("ffmpegLibVersion", null),
        )
    }

    @Test(timeout = 120_000)
    fun installedFfmpegEncodesAndDecodesLosslessWebp() {
        YoutubeDL.init(context)
        FFmpeg.init(context)
        val directory = File(context.cacheDir, "webp-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1)
            val input = File(directory, "source.ppm").apply {
                writeBytes("P6\n2 2\n255\n".toByteArray() + rgb)
            }
            val webp = File(directory, "encoded.webp")
            val decoded = File(directory, "decoded.rgb")
            val binary = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
            fun convert(vararg arguments: String) {
                val log = File(directory, "ffmpeg.log")
                val builder =
                    ProcessBuilder(listOf(binary.path, "-nostdin", "-hide_banner", "-y") + arguments)
                        .redirectErrorStream(true)
                        .redirectOutput(log)
                builder.environment()["LD_LIBRARY_PATH"] =
                    listOf("python", "ffmpeg", "aria2c").joinToString(":") {
                        File(packages, "$it/usr/lib").path
                    }
                val process = builder.start()
                try {
                    val exitCode = process.waitFor()
                    assertEquals(log.readText(), 0, exitCode)
                } finally {
                    process.destroy()
                }
            }
            convert("-i", input.path, "-c:v", "libwebp", "-lossless", "1", "-pix_fmt", "bgra", webp.path)
            assertTrue(webp.length() > 0)
            convert("-i", webp.path, "-f", "rawvideo", "-pix_fmt", "rgb24", decoded.path)
            assertArrayEquals(rgb, decoded.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }
}
