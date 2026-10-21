package com.junkfood.seal

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.download.Task
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.FileUtil.createEmptyFile
import com.junkfood.seal.util.FileUtil.getFileName
import com.junkfood.seal.util.FileUtil.getFileSize
import com.tencent.mmkv.MMKV
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** All media is generated locally. The native downloader can reach only loopback. */
@RunWith(AndroidJUnit4::class)
class OfflineDownloadTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mediaPathsPreserveSpacesAndUnicodeAndGrantReadAccess() {
        val directory = File(context.cacheDir, "media-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file =
                File(directory, "नमस्ते video clip.mp4").apply {
                    writeBytes(byteArrayOf(1, 2, 3, 4))
                }
            assertEquals("नमस्ते video clip", file.path.getFileName())
            assertEquals(4L, file.path.getFileSize())
            val opening = FileUtil.createIntentForOpeningFile(file.path)!!
            assertEquals(Intent.ACTION_VIEW, opening.action)
            assertEquals("video/mp4", context.contentResolver.getType(opening.data!!))
            assertEquals("content", opening.data!!.scheme)
            assertEquals("${context.packageName}.provider", opening.data!!.authority)
            assertArrayEquals(
                byteArrayOf(1, 2, 3, 4),
                context.contentResolver.openInputStream(opening.data!!)!!.use { it.readBytes() },
            )
            assertTrue(opening.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val sharing = FileUtil.createIntentForSharingFile(file.path)!!
            assertEquals(Intent.ACTION_SEND, sharing.action)
            assertTrue(sharing.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertNull(FileUtil.createIntentForOpeningFile(null))
            assertNull(FileUtil.createIntentForOpeningFile(File(directory, "missing.mp4").path))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun temporaryCleanupPreservesHiddenFilesAndDirectoryStructure() {
        val root = File(context.cacheDir, "cleanup-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val nested = File(root, "nested")
            val media =
                nested.createEmptyFile("audio.opus").getOrThrow().apply { writeText("fixture") }
            val hidden = root.createEmptyFile(".nomedia").getOrThrow()
            assertEquals(1, FileUtil.clearTempFiles(root))
            assertFalse(media.exists())
            assertTrue(hidden.exists())
            assertTrue(nested.isDirectory)
            assertEquals(0, FileUtil.clearTempFiles(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun taskStateSurvivesNativeMmkvCloseAndReopen() {
        val id = "instrumentation-${UUID.randomUUID()}"
        val task =
            Task(
                "http://127.0.0.1/fixture.wav",
                preferences =
                    DownloadPreferences.EMPTY.copy(
                        extractAudio = true,
                        outputTemplate = "%(title)s.%(ext)s",
                    ),
            )
        val state =
            Task.State(
                Task.DownloadState.Completed("/local/नमस्ते clip.opus"),
                null,
                Task.ViewState(title = "Saved fixture"),
            )
        var store = MMKV.mmkvWithID(id)!!
        try {
            assertTrue(store.encode("task", Json.encodeToString(task)))
            assertTrue(store.encode("state", Json.encodeToString(state)))
            store.sync()
            store.close()
            store = MMKV.mmkvWithID(id)!!
            assertEquals(task, Json.decodeFromString<Task>(store.decodeString("task")!!))
            assertEquals(state, Json.decodeFromString<Task.State>(store.decodeString("state")!!))
        } finally {
            store.clearAll()
            store.close()
            MMKV.removeStorage(id)
        }
    }

    @Test(timeout = 120_000)
    fun bundledPythonAndYtdlpDownloadGeneratedAudioOverLoopback() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        check(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            "Run offline instrumentation with external WiFi and mobile data disabled"
        }
        val directory =
            File(context.cacheDir, "download-test-${UUID.randomUUID()}").apply { mkdirs() }
        val body =
            ByteBuffer.allocate(44 + 1600)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray())
                    putInt(36 + 1600)
                    put("WAVEfmt ".toByteArray())
                    putInt(16)
                    putShort(1)
                    putShort(1)
                    putInt(8000)
                    putInt(16000)
                    putShort(2)
                    putShort(16)
                    put("data".toByteArray())
                    putInt(1600)
                    put(ByteArray(1600))
                }
                .array()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serverFailure = AtomicReference<Throwable?>()
        val serving =
            thread(name = "offline-media-fixture") {
                try {
                    while (!server.isClosed) server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine() ?: return@use
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) line = reader.readLine()
                        socket.getOutputStream().apply {
                            write(
                                "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                    .toByteArray()
                            )
                            if (!request.startsWith("HEAD ")) write(body)
                            flush()
                        }
                    }
                } catch (error: Throwable) {
                    if (!server.isClosed) serverFailure.set(error)
                }
            }
        try {
            YoutubeDL.init(context)
            val version = YoutubeDL.execute(YoutubeDLRequest(emptyList()).addOption("--version"))
            assertEquals(0, version.exitCode)
            assertTrue(version.out.trim().matches(Regex("\\d{4}\\.\\d{2}\\.\\d{2}.*")))
            val request =
                YoutubeDLRequest("http://127.0.0.1:${server.localPort}/fixture.wav")
                    .addOption("--ignore-config")
                    .addOption("--no-playlist")
                    .addOption("--socket-timeout", 5)
                    .addOption("--retries", 0)
                    .addOption("-P", directory.path)
                    .addOption("-o", "fixture.%(ext)s")
            assertEquals(0, YoutubeDL.execute(request).exitCode)
            assertArrayEquals(body, File(directory, "fixture.wav").readBytes())
            FFmpeg.init(context)
            val conversion =
                YoutubeDLRequest("http://127.0.0.1:${server.localPort}/fixture.wav")
                    .addOption("--ignore-config")
                    .addOption("--no-playlist")
                    .addOption("--socket-timeout", 5)
                    .addOption("--retries", 0)
                    .addOption("-P", directory.path)
                    .addOption("-o", "converted.%(ext)s")
                    .addOption("-x")
                    .addOption("--audio-format", "mp3")
            assertEquals(0, YoutubeDL.execute(conversion).exitCode)
            val mp3 = File(directory, "converted.mp3").readBytes()
            assertTrue(mp3.size > 128)
            assertEquals("ID3", String(mp3.copyOfRange(0, 3), Charsets.US_ASCII))
            assertNull(serverFailure.get())
        } finally {
            server.close()
            serving.join(5_000)
            directory.deleteRecursively()
        }
    }
}
