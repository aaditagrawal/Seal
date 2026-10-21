package com.junkfood.seal

import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.util.*
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.DownloadUtil.addOptionsForAudioDownloads
import com.junkfood.seal.util.DownloadUtil.addOptionsForVideoDownloads
import com.junkfood.seal.util.DownloadUtil.toFormatSorter
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DownloadConfigurationTest {
    private val defaults = DownloadPreferences.EMPTY
    private val video = Format(formatId = "137", vcodec = "avc1", acodec = "none", fileSize = 100.0)
    private val audio =
        Format(formatId = "140", vcodec = "none", acodec = "aac", fileSizeApprox = 25.0)
    private val info =
        VideoInfo(
            originalUrl = "https://offline.invalid/video",
            title = "Original",
            duration = 12.6,
        )

    @Test
    fun compatibilitySortKeepsProtocolAndCodecAheadOfResolution() {
        val resolutions =
            listOf("", "res:2160", "res:1440", "res:1080", "res:720", "res:480", "res:360", "+res")
        resolutions.forEachIndexed { index, resolution ->
            val result =
                defaults
                    .copy(videoFormat = FORMAT_COMPATIBILITY, videoResolution = index)
                    .toFormatSorter()
            assertEquals(
                listOf("proto,vcodec:h264,ext", resolution)
                    .filter { it.isNotEmpty() }
                    .joinToString(","),
                result,
            )
        }
    }

    @Test
    fun qualitySortUsesAv1OnlyWhenHardwareSupportIsEnabled() {
        assertEquals(
            "res:1080,vcodec:vp9.2",
            defaults.copy(videoFormat = FORMAT_QUALITY, videoResolution = 3).toFormatSorter(),
        )
        assertEquals(
            "res:1080,vcodec:av01",
            defaults
                .copy(
                    videoFormat = FORMAT_QUALITY,
                    videoResolution = 3,
                    supportAv1HardwareDecoding = true,
                )
                .toFormatSorter(),
        )
    }

    @Test
    fun audioPresetIsOptInAndPreservesCodecAndBitrate() {
        assertEquals("", defaults.copy(audioFormat = OPUS, audioQuality = LOW).toFormatSorter())
        for ((format, codec) in listOf(M4A to "aac", OPUS to "opus")) {
            for ((quality, bitrate) in listOf(HIGH to 192, MEDIUM to 128, LOW to 64)) {
                assertEquals(
                    "acodec:$codec,abr~$bitrate",
                    defaults
                        .copy(
                            useCustomAudioPreset = true,
                            audioFormat = format,
                            audioQuality = quality,
                        )
                        .toFormatSorter(),
                )
            }
        }
    }

    @Test
    fun selectedStreamsOverrideSorterAndEnableMultistreamAudio() {
        val request =
            YoutubeDLRequest(info.originalUrl!!)
                .addOptionsForVideoDownloads(
                    defaults.copy(
                        formatIdString = "137+140+141",
                        mergeAudioStream = true,
                        formatSorting = true,
                        sortingFields = "res:720",
                    )
                )
        val args = request.buildCommand()
        assertEquals("137+140+141", args[args.indexOf("-f") + 1])
        assertTrue(args.contains("--audio-multistreams"))
        assertFalse(args.contains("-S"))
    }

    @Test
    fun subtitlesAndMkvOptionsReachRealRequestBuilder() {
        val args =
            YoutubeDLRequest(info.originalUrl!!)
                .addOptionsForVideoDownloads(
                    defaults.copy(
                        downloadSubtitle = true,
                        autoSubtitle = true,
                        subtitleLanguage = "en,hi",
                        embedSubtitle = true,
                        keepSubtitle = true,
                        convertSubtitle = CONVERT_SRT,
                        mergeToMkv = true,
                    )
                )
                .buildCommand()
        for (option in
            listOf(
                "--write-auto-subs",
                "--embed-subs",
                "--write-subs",
                "--remux-video",
                "--merge-output-format",
            )) assertTrue(args.contains(option))
        assertEquals("en,hi", args[args.indexOf("--sub-langs") + 1])
        assertEquals("srt", args[args.indexOf("--convert-subs") + 1])
        assertEquals("youtube:skip=translated_subs", args[args.indexOf("--extractor-args") + 1])
    }

    @Test
    fun clipsDoNotAutomaticallyEmbedFullVideoChapters() {
        assertFalse(
            YoutubeDLRequest(info.originalUrl!!)
                .addOptionsForVideoDownloads(defaults.copy(videoClips = listOf(VideoClip(2, 5))))
                .buildCommand()
                .contains("--embed-chapters")
        )
        assertTrue(
            YoutubeDLRequest(info.originalUrl!!)
                .addOptionsForVideoDownloads(defaults)
                .buildCommand()
                .contains("--embed-chapters")
        )
    }

    @Test
    fun audioConversionAndPlaylistMetadataArePassedAsSeparateArguments() {
        val args =
            YoutubeDLRequest(info.originalUrl!!)
                .addOptionsForAudioDownloads(
                    "id",
                    defaults.copy(convertAudio = true, audioConvertFormat = CONVERT_MP3),
                    "https://offline.invalid/playlist",
                )
                .buildCommand()
        assertTrue(args.contains("-x"))
        assertEquals("mp3", args[args.indexOf("--audio-format") + 1])
        assertTrue(args.contains("%(album,playlist,title)s:%(meta_album)s"))
        assertTrue(args.contains("%(track_number,playlist_index)d:%(meta_track)s"))
    }

    @Test
    fun configuredTaskKeepsTitleClipsSizeAndSubtitleSelections() {
        val result =
            TaskFactory.createWithConfigurations(
                info,
                listOf(video, audio),
                listOf(VideoClip(2, 5)),
                true,
                "New title",
                listOf("en"),
                listOf("hi"),
                defaults,
            )
        assertEquals("137+140", result.task.preferences.formatIdString)
        assertEquals(125.0, result.state.videoInfo!!.fileSize!!, 0.0)
        assertEquals("New title", result.state.viewState.title)
        assertEquals(13, result.state.viewState.duration)
        assertEquals(listOf(video), result.state.viewState.videoFormats)
        assertEquals(listOf(audio), result.state.viewState.audioOnlyFormats)
        assertEquals("en,hi", result.task.preferences.subtitleLanguage)
        assertTrue(result.task.preferences.autoSubtitle)
        assertTrue(result.task.preferences.downloadSubtitle)
        assertFalse(result.task.preferences.extractAudio)
        assertEquals(listOf(VideoClip(2, 5)), result.task.preferences.videoClips)
        assertEquals(Task.DownloadState.ReadyWithInfo, result.state.downloadState)
    }

    @Test
    fun audioOnlySelectionPreservesDefaultsAndEnablesExtraction() {
        val base =
            defaults.copy(
                proxy = true,
                proxyUrl = "http://127.0.0.1:9",
                privateMode = true,
                downloadSubtitle = true,
                subtitleLanguage = "ja",
            )
        val result =
            TaskFactory.createWithConfigurations(
                info,
                listOf(audio, audio.copy(formatId = "141")),
                emptyList(),
                false,
                "",
                emptyList(),
                emptyList(),
                base,
            )
        assertEquals("Original", result.state.viewState.title)
        assertTrue(result.task.preferences.extractAudio)
        assertTrue(result.task.preferences.mergeAudioStream)
        assertTrue(result.task.preferences.privateMode)
        assertEquals(base.proxyUrl, result.task.preferences.proxyUrl)
        assertEquals("ja", result.task.preferences.subtitleLanguage)
    }

    @Test
    fun playlistSelectionUsesOneBasedIndexesAndDeduplicatesSelection() {
        val playlist =
            PlaylistResult(
                title = "Collection",
                channel = "Channel",
                entries =
                    listOf(
                        PlaylistEntry(url = "first", title = "One"),
                        PlaylistEntry(url = "second", title = null, duration = 3.6),
                    ),
            )
        val tasks =
            TaskFactory.createWithPlaylistResult("playlist", listOf(2, 1, 2), playlist, defaults)
        assertEquals(
            listOf(Task.TypeInfo.Playlist(2), Task.TypeInfo.Playlist(1)),
            tasks.map { it.task.type },
        )
        assertEquals("Collection - 2", tasks[0].state.viewState.title)
        assertEquals("Channel", tasks[0].state.viewState.uploader)
        assertEquals(4, tasks[0].state.viewState.duration)
        assertEquals("second", tasks[0].state.viewState.url)
        assertTrue(
            tasks.all {
                it.task.url == "playlist" && it.state.downloadState == Task.DownloadState.Idle
            }
        )
        assertTrue(
            TaskFactory.createWithPlaylistResult("playlist", emptyList(), playlist, defaults)
                .isEmpty()
        )
        assertThrows(IndexOutOfBoundsException::class.java) {
            TaskFactory.createWithPlaylistResult("playlist", listOf(0), playlist, defaults)
        }
        assertThrows(IllegalStateException::class.java) {
            TaskFactory.createWithPlaylistResult(
                "playlist",
                listOf(1),
                playlist.copy(entries = null),
                defaults,
            )
        }
    }

    @Test
    fun customCommandAndPreferencesRoundTripThroughPersistence() {
        val original =
            Task(
                "https://offline.invalid",
                Task.TypeInfo.CustomCommand(CommandTemplate(7, "Audio", "-x --audio-format opus")),
                defaults.copy(
                    videoClips = listOf(VideoClip(2, 9)),
                    sdcard = true,
                    sdcardUri = "content://offline/tree/folder",
                    outputTemplate = "%(title)s.%(ext)s",
                ),
            )
        val restored = Json.decodeFromString<Task>(Json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(original.id, restored.id)
        assertNotEquals(
            original.id,
            Task(original.url, original.type, original.preferences.copy(extractAudio = true)).id,
        )
    }

    @Test
    fun persistedRunningStateRestoresProgressWithoutSerializingCoroutineJob() {
        val job = Job()
        val state =
            Task.State(
                Task.DownloadState.Running(job, "task-id", 42.5f, "42%"),
                info,
                Task.ViewState.fromVideoInfo(info),
            )
        val encoded = Json.encodeToString(state)
        assertFalse(encoded.contains("job"))
        val restored = Json.decodeFromString<Task.State>(encoded)
        val running = restored.downloadState as Task.DownloadState.Running
        assertEquals(42.5f, running.progress)
        assertEquals("task-id", running.taskId)
        assertNotSame(job, running.job)
        assertEquals(Task.RestartableAction.Download, running.action)
        assertEquals(state.viewState, restored.viewState)
    }
}
