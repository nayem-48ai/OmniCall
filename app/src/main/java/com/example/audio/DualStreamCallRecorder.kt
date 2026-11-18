package com.example.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.data.repository.RecordingItem
import com.example.data.repository.RecordingRepository
import com.example.domain.ai.AudioStreamTap
import com.example.domain.ai.CallEventBus
import com.example.domain.ai.OmniCallEvent
import com.example.domain.model.CallSourceType
import com.example.domain.model.RecordingEngine
import com.example.domain.model.RecordingFormat
import com.example.domain.model.RecordingType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.min

/**
 * Local Call Recorder (Scoped Storage Compliant)
 * ----------------------------------------------
 * Supports manual & Auto Call Recording across both Cellular SIM and SIP VoIP calls.
 *
 * Anti-Silence & OEM Compatibility Architecture:
 * - On Android 10–14+, privileged AudioSources (VOICE_CALL, VOICE_DOWNLINK, VOICE_UPLINK) often
 *   initialize cleanly but emit 100% digital zeros from AudioFlinger. Every AudioRecord source is
 *   actively probed (~140ms) for genuine non-zero PCM signal before acceptance.
 * - Supports full [MediaRecorder] (`AudioSource.MIC` / `VOICE_RECOGNITION` / `VOICE_COMMUNICATION`)
 *   encoding to `.m4a` both directly ([RecordingEngine.MEDIA_RECORDER_MIC]) and as an automatic
 *   runtime fallback ([RecordingEngine.AUTO]) whenever AudioRecord sources are silenced by an OEM HAL.
 * - Supports all 3 [RecordingType] modes (Both Sides, Only Internal Voice, Only External Voice).
 */
class DualStreamCallRecorder(
    private val context: Context,
    private val audioStreamTap: AudioStreamTap,
    private val recordingRepository: RecordingRepository,
    private val eventBus: CallEventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recordingJob: Job? = null
    private var uplinkCollectorJob: Job? = null
    private var downlinkCollectorJob: Job? = null
    private var simAudioRecord: AudioRecord? = null
    private var simMediaRecorder: MediaRecorder? = null

    private val uplinkQueue = ConcurrentLinkedQueue<ShortArray>()
    private val downlinkQueue = ConcurrentLinkedQueue<ShortArray>()

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    private var usingMediaRecorderFallback: Boolean = false

    private var currentCallId: String = ""
    private var currentRemoteHandle: String = ""
    private var currentDisplayName: String = ""
    private var currentSourceLabel: String = ""
    private var currentRequestedFormat: RecordingFormat = RecordingFormat.AAC_M4A
    private var currentRecordingType: RecordingType = RecordingType.INTERNAL_AND_EXTERNAL
    private var currentSampleRate: Int = 16000
    private var currentWavFile: File? = null
    private var currentTargetM4aFile: File? = null
    private var currentMediaRecorderFile: File? = null
    private var startedAtMs: Long = 0L

    @SuppressLint("MissingPermission")
    suspend fun startRecording(
        callId: String,
        remoteHandle: String,
        displayName: String,
        sourceType: CallSourceType,
        sourceLabel: String,
        format: RecordingFormat,
        recordingType: RecordingType = RecordingType.INTERNAL_AND_EXTERNAL,
        recordingEngine: RecordingEngine = RecordingEngine.AUTO
    ): Boolean = withContext(Dispatchers.IO) {
        if (isRecording) return@withContext true

        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return@withContext false

        val dir = File(context.filesDir, "recordings").apply { if (!exists()) mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeParty = remoteHandle.replace(Regex("[^A-Za-z0-9+]"), "_").take(20).ifBlank { "call" }

        val wavFile = File(dir, "OmniCall_${safeParty}_$stamp.wav")
        val m4aFile = File(dir, "OmniCall_${safeParty}_$stamp.m4a")
        val candidateSources = buildAudioSourceCandidates(recordingType)

        usingMediaRecorderFallback = false
        currentMediaRecorderFile = null

        var detectedSampleRate = 16000
        var activeSourceIndex = 0
        var activeAudioRecordStarted = false

        if (sourceType != CallSourceType.SIP) {
            if (recordingEngine == RecordingEngine.MEDIA_RECORDER_MIC) {
                val startedMr = startSimMediaRecorder(m4aFile, recordingType)
                if (!startedMr) return@withContext false
                usingMediaRecorderFallback = true
                currentMediaRecorderFile = m4aFile
                detectedSampleRate = 44100
            } else {
                val opened = openBestSimAudioRecord(
                    sources = candidateSources,
                    startFromIndex = 0,
                    requireNonZeroSignal = true
                )
                if (opened != null) {
                    simAudioRecord = opened.recorder
                    detectedSampleRate = opened.sampleRate
                    activeSourceIndex = opened.sourceIndex
                    activeAudioRecordStarted = true
                } else if (recordingEngine == RecordingEngine.AUTO) {
                    // Fallback directly to MediaRecorder(AudioSource.MIC)
                    val startedMr = startSimMediaRecorder(m4aFile, recordingType)
                    if (!startedMr) {
                        // Last-resort: accept an initialized AudioRecord even without probe signal
                        val rawOpened = openBestSimAudioRecord(
                            sources = candidateSources,
                            startFromIndex = 0,
                            requireNonZeroSignal = false
                        ) ?: return@withContext false
                        simAudioRecord = rawOpened.recorder
                        detectedSampleRate = rawOpened.sampleRate
                        activeSourceIndex = rawOpened.sourceIndex
                        activeAudioRecordStarted = true
                    } else {
                        usingMediaRecorderFallback = true
                        currentMediaRecorderFile = m4aFile
                        detectedSampleRate = 44100
                    }
                } else {
                    val rawOpened = openBestSimAudioRecord(
                        sources = candidateSources,
                        startFromIndex = 0,
                        requireNonZeroSignal = false
                    ) ?: return@withContext false
                    simAudioRecord = rawOpened.recorder
                    detectedSampleRate = rawOpened.sampleRate
                    activeSourceIndex = rawOpened.sourceIndex
                    activeAudioRecordStarted = true
                }
            }
        }

        currentCallId = callId
        currentRemoteHandle = remoteHandle
        currentDisplayName = displayName.ifBlank { remoteHandle }
        currentSourceLabel = "$sourceLabel • ${recordingType.shortLabel}"
        currentRequestedFormat = format
        currentRecordingType = recordingType
        currentSampleRate = detectedSampleRate
        currentWavFile = wavFile
        currentTargetM4aFile = if (format == RecordingFormat.AAC_M4A) m4aFile else null
        startedAtMs = System.currentTimeMillis()
        isRecording = true
        uplinkQueue.clear()
        downlinkQueue.clear()

        if (sourceType == CallSourceType.SIP) {
            if (recordingType == RecordingType.INTERNAL_AND_EXTERNAL ||
                recordingType == RecordingType.ONLY_EXTERNAL
            ) {
                uplinkCollectorJob = scope.launch {
                    audioStreamTap.uplinkFrames.collect { frame ->
                        if (isRecording) {
                            if (uplinkQueue.size > 96) uplinkQueue.poll()
                            uplinkQueue.offer(frame.pcm16)
                        }
                    }
                }
            }
            if (recordingType == RecordingType.INTERNAL_AND_EXTERNAL ||
                recordingType == RecordingType.ONLY_INTERNAL
            ) {
                downlinkCollectorJob = scope.launch {
                    audioStreamTap.downlinkFrames.collect { frame ->
                        if (isRecording) {
                            if (downlinkQueue.size > 96) downlinkQueue.poll()
                            downlinkQueue.offer(frame.pcm16)
                        }
                    }
                }
            }
            recordingJob = scope.launch {
                writeLiveWavStream(
                    outFile = wavFile,
                    sourceType = sourceType,
                    recordingType = recordingType,
                    recordingEngine = recordingEngine,
                    initialSampleRate = detectedSampleRate,
                    candidateSources = candidateSources,
                    initialSourceIndex = activeSourceIndex,
                    fallbackM4aFile = m4aFile
                )
            }
        } else if (activeAudioRecordStarted) {
            recordingJob = scope.launch {
                writeLiveWavStream(
                    outFile = wavFile,
                    sourceType = sourceType,
                    recordingType = recordingType,
                    recordingEngine = recordingEngine,
                    initialSampleRate = detectedSampleRate,
                    candidateSources = candidateSources,
                    initialSourceIndex = activeSourceIndex,
                    fallbackM4aFile = m4aFile
                )
            }
        }
        true
    }

    private fun buildAudioSourceCandidates(recordingType: RecordingType): List<Int> {
        return when (recordingType) {
            RecordingType.INTERNAL_AND_EXTERNAL -> listOf(
                MediaRecorder.AudioSource.VOICE_CALL,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.DEFAULT
            )
            RecordingType.ONLY_INTERNAL -> listOf(
                MediaRecorder.AudioSource.VOICE_DOWNLINK,
                MediaRecorder.AudioSource.VOICE_CALL,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.MIC
            )
            RecordingType.ONLY_EXTERNAL -> listOf(
                MediaRecorder.AudioSource.VOICE_UPLINK,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.DEFAULT
            )
        }
    }

    private fun buildMediaRecorderSources(recordingType: RecordingType): List<Int> {
        return when (recordingType) {
            RecordingType.INTERNAL_AND_EXTERNAL -> listOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.VOICE_CALL
            )
            RecordingType.ONLY_INTERNAL -> listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_DOWNLINK
            )
            RecordingType.ONLY_EXTERNAL -> listOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER
            )
        }
    }

    private data class OpenedSimRecorder(
        val recorder: AudioRecord,
        val sampleRate: Int,
        val sourceIndex: Int,
        val hasSignal: Boolean
    )

    @SuppressLint("MissingPermission")
    private fun openBestSimAudioRecord(
        sources: List<Int>,
        startFromIndex: Int,
        requireNonZeroSignal: Boolean
    ): OpenedSimRecorder? {
        val rates = listOf(16000, 44100, 48000, 8000)
        var firstInitializedSilentBackup: OpenedSimRecorder? = null

        for (srcIdx in startFromIndex until sources.size) {
            val source = sources[srcIdx]
            for (rate in rates) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue
                val bufBytes = minBuf.coerceAtLeast((rate / 50) * 8)
                val rec = runCatching {
                    AudioRecord(
                        source,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufBytes
                    )
                }.getOrNull() ?: continue

                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    val started = runCatching {
                        rec.startRecording()
                        rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
                    }.getOrDefault(false)

                    if (started) {
                        // Probe ~140ms across 7 chunks to verify HAL is NOT filling 0x0000 privacy silence
                        val chunkLen = (rate / 50).coerceAtLeast(160)
                        val probeBuf = ShortArray(chunkLen)
                        var nonZeroSamples = 0
                        var totalRead = 0
                        for (attempt in 0 until 7) {
                            val r = runCatching {
                                rec.read(probeBuf, 0, probeBuf.size, AudioRecord.READ_BLOCKING)
                            }.getOrDefault(-1)
                            if (r > 0) {
                                totalRead += r
                                for (i in 0 until r) {
                                    if (probeBuf[i].toInt() != 0) {
                                        nonZeroSamples++
                                    }
                                }
                            }
                            if (nonZeroSamples >= 6) break
                        }

                        if (nonZeroSamples >= 6) {
                            firstInitializedSilentBackup?.let { backup ->
                                runCatching { backup.recorder.stop(); backup.recorder.release() }
                            }
                            return OpenedSimRecorder(rec, rate, srcIdx, hasSignal = true)
                        }

                        if (!requireNonZeroSignal && totalRead >= 0 && firstInitializedSilentBackup == null) {
                            firstInitializedSilentBackup = OpenedSimRecorder(rec, rate, srcIdx, hasSignal = false)
                            break // move to next AudioSource rather than releasing yet
                        }
                    }
                }
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
        }
        return if (requireNonZeroSignal) null else firstInitializedSilentBackup
    }

    @Suppress("DEPRECATION")
    private fun startSimMediaRecorder(outFile: File, recordingType: RecordingType): Boolean {
        val sources = buildMediaRecorderSources(recordingType)
        for (src in sources) {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            val started = runCatching {
                mr.setAudioSource(src)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioSamplingRate(44100)
                mr.setAudioEncodingBitRate(96000)
                mr.setAudioChannels(1)
                mr.setOutputFile(outFile.absolutePath)
                mr.prepare()
                mr.start()
                true
            }.getOrDefault(false)

            if (started) {
                simMediaRecorder = mr
                return true
            }
            runCatching { mr.reset() }
            runCatching { mr.release() }
        }
        return false
    }

    suspend fun stopRecording(): RecordingItem? = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext null
        isRecording = false

        uplinkCollectorJob?.cancel()
        downlinkCollectorJob?.cancel()
        uplinkCollectorJob = null
        downlinkCollectorJob = null

        runCatching {
            simAudioRecord?.stop()
            simAudioRecord?.release()
        }
        simAudioRecord = null

        recordingJob?.join()
        recordingJob = null

        val mrFile = currentMediaRecorderFile
        if (simMediaRecorder != null) {
            runCatching { simMediaRecorder?.stop() }
            runCatching { simMediaRecorder?.reset() }
            runCatching { simMediaRecorder?.release() }
            simMediaRecorder = null
        }

        val durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1000L)

        // If MediaRecorder captured the recording (direct or via runtime fallback)
        if (usingMediaRecorderFallback && mrFile != null && mrFile.exists() && mrFile.length() > 256L) {
            currentWavFile?.let { if (it.exists()) runCatching { it.delete() } }
            val item = RecordingItem(
                id = 0L,
                fileName = mrFile.name,
                filePath = mrFile.absolutePath,
                format = RecordingFormat.AAC_M4A,
                sampleRateHz = currentSampleRate,
                durationMs = durationMs,
                fileSizeBytes = mrFile.length(),
                remoteHandle = currentRemoteHandle,
                remoteDisplayName = currentDisplayName,
                sourceLabel = "$currentSourceLabel • MIC",
                createdAtMs = startedAtMs
            )
            val insertedId = recordingRepository.saveRecording(item)
            eventBus.publish(
                OmniCallEvent.RecordingCompleted(currentCallId, mrFile.absolutePath, durationMs)
            )
            return@withContext item.copy(id = insertedId)
        }

        val wavFile = currentWavFile ?: return@withContext null
        if (!wavFile.exists() || wavFile.length() <= 44L) {
            runCatching { wavFile.delete() }
            return@withContext null
        }

        var finalFile = wavFile
        var finalFormat = RecordingFormat.WAV
        val targetM4a = currentTargetM4aFile

        if (currentRequestedFormat == RecordingFormat.AAC_M4A && targetM4a != null) {
            val encoded = transcodeWavToM4a(wavFile, targetM4a, currentSampleRate)
            if (encoded && targetM4a.exists() && targetM4a.length() > 256L) {
                runCatching { wavFile.delete() }
                finalFile = targetM4a
                finalFormat = RecordingFormat.AAC_M4A
            } else {
                runCatching { targetM4a.delete() }
            }
        }

        val item = RecordingItem(
            id = 0L,
            fileName = finalFile.name,
            filePath = finalFile.absolutePath,
            format = finalFormat,
            sampleRateHz = currentSampleRate,
            durationMs = durationMs,
            fileSizeBytes = finalFile.length(),
            remoteHandle = currentRemoteHandle,
            remoteDisplayName = currentDisplayName,
            sourceLabel = currentSourceLabel,
            createdAtMs = startedAtMs
        )
        val insertedId = recordingRepository.saveRecording(item)
        eventBus.publish(
            OmniCallEvent.RecordingCompleted(currentCallId, finalFile.absolutePath, durationMs)
        )
        item.copy(id = insertedId)
    }

    private suspend fun writeLiveWavStream(
        outFile: File,
        sourceType: CallSourceType,
        recordingType: RecordingType,
        recordingEngine: RecordingEngine,
        initialSampleRate: Int,
        candidateSources: List<Int>,
        initialSourceIndex: Int,
        fallbackM4aFile: File
    ) {
        var sampleRate = initialSampleRate
        var sourceIndex = initialSourceIndex
        var frameSamples = (sampleRate / 50).coerceAtLeast(160) // 20ms frame
        var simBuf = ShortArray(frameSamples)
        var consecutiveSimFailures = 0
        var consecutiveZeroFrames = 0
        var anyNonZeroWritten = false

        FileOutputStream(outFile).use { fos ->
            fos.write(ByteArray(44))
            var totalAudioBytes = 0L

            while (isRecording ||
                (sourceType == CallSourceType.SIP && (uplinkQueue.isNotEmpty() || downlinkQueue.isNotEmpty()))
            ) {
                val pcmFrame: ShortArray = if (sourceType == CallSourceType.SIP) {
                    val pulled = pullNextSipFrame(recordingType, frameSamples)
                    if (pulled != null) {
                        if (isRecording && uplinkQueue.size < 2 && downlinkQueue.size < 2) delay(15)
                        pulled
                    } else {
                        delay(20)
                        if (!isRecording) break
                        ShortArray(frameSamples)
                    }
                } else {
                    val rec = simAudioRecord
                    if (rec == null) {
                        if (usingMediaRecorderFallback) break
                        delay(20)
                        if (!isRecording) break
                        ShortArray(frameSamples)
                    } else {
                        val read = runCatching {
                            rec.read(simBuf, 0, simBuf.size, AudioRecord.READ_BLOCKING)
                        }.getOrDefault(-1)
                        if (read > 0) {
                            consecutiveSimFailures = 0
                            var maxAmp = 0
                            for (i in 0 until read) {
                                val v = abs(simBuf[i].toInt())
                                if (v > maxAmp) maxAmp = v
                            }
                            if (maxAmp == 0) {
                                consecutiveZeroFrames++
                            } else {
                                consecutiveZeroFrames = 0
                                anyNonZeroWritten = true
                            }

                            // If current source is returning pure digital silence (>0.7s), switch sources or MediaRecorder(MIC)
                            if (consecutiveZeroFrames >= 35 && isRecording) {
                                runCatching { rec.stop(); rec.release() }
                                simAudioRecord = null
                                val reopened = if (sourceIndex + 1 < candidateSources.size) {
                                    openBestSimAudioRecord(
                                        sources = candidateSources,
                                        startFromIndex = sourceIndex + 1,
                                        requireNonZeroSignal = true
                                    )
                                } else null

                                if (reopened != null) {
                                    simAudioRecord = reopened.recorder
                                    sourceIndex = reopened.sourceIndex
                                    if (!anyNonZeroWritten && reopened.sampleRate != sampleRate && totalAudioBytes == 0L) {
                                        sampleRate = reopened.sampleRate
                                        currentSampleRate = sampleRate
                                        frameSamples = (sampleRate / 50).coerceAtLeast(160)
                                        simBuf = ShortArray(frameSamples)
                                    }
                                    consecutiveZeroFrames = 0
                                } else if (recordingEngine == RecordingEngine.AUTO && !anyNonZeroWritten) {
                                    // Escalate to Android MediaRecorder(AudioSource.MIC)
                                    if (startSimMediaRecorder(fallbackM4aFile, recordingType)) {
                                        usingMediaRecorderFallback = true
                                        currentMediaRecorderFile = fallbackM4aFile
                                        currentSampleRate = 44100
                                        break
                                    }
                                }
                            }

                            applyAdaptiveVoiceGain(simBuf.copyOf(read), maxAmp)
                        } else {
                            consecutiveSimFailures++
                            if (consecutiveSimFailures >= 12 && isRecording) {
                                runCatching { rec.stop(); rec.release() }
                                simAudioRecord = null
                                val reopened = if (sourceIndex + 1 < candidateSources.size) {
                                    openBestSimAudioRecord(
                                        sources = candidateSources,
                                        startFromIndex = sourceIndex + 1,
                                        requireNonZeroSignal = true
                                    )
                                } else null

                                if (reopened != null) {
                                    simAudioRecord = reopened.recorder
                                    sourceIndex = reopened.sourceIndex
                                    consecutiveSimFailures = 0
                                } else if (recordingEngine == RecordingEngine.AUTO) {
                                    if (startSimMediaRecorder(fallbackM4aFile, recordingType)) {
                                        usingMediaRecorderFallback = true
                                        currentMediaRecorderFile = fallbackM4aFile
                                        currentSampleRate = 44100
                                        break
                                    }
                                }
                            }
                            delay(20)
                            if (!isRecording) break
                            ShortArray(frameSamples)
                        }
                    }
                }

                val bytes = ByteArray(pcmFrame.size * 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcmFrame)
                fos.write(bytes)
                totalAudioBytes += bytes.size
            }
            fos.flush()
            patchWavHeader(outFile, totalAudioBytes, sampleRate, 1, 16)
        }
    }

    /**
     * Dynamic adaptive gain normalizing quiet cellular helpline / earpiece-bleed capture up toward
     * audible speech levels while keeping pure floor hiss un-amplified.
     */
    private fun applyAdaptiveVoiceGain(samples: ShortArray, maxAmp: Int): ShortArray {
        if (maxAmp <= 12 || maxAmp > 12000) return samples
        val gain = (12000f / maxAmp.toFloat()).coerceIn(1.2f, 18.0f)
        return ShortArray(samples.size) { idx ->
            (samples[idx] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun pullNextSipFrame(
        recordingType: RecordingType,
        frameSamples: Int
    ): ShortArray? {
        return when (recordingType) {
            RecordingType.ONLY_INTERNAL -> downlinkQueue.poll()
            RecordingType.ONLY_EXTERNAL -> uplinkQueue.poll()
            RecordingType.INTERNAL_AND_EXTERNAL -> {
                if (uplinkQueue.isEmpty() && downlinkQueue.isEmpty()) return null
                val up = uplinkQueue.poll()
                val down = downlinkQueue.poll()
                if (up == null && down == null) return null
                val len = maxOf(up?.size ?: 0, down?.size ?: 0).coerceAtLeast(frameSamples)
                ShortArray(len) { idx ->
                    val u = if (up != null && idx < up.size) up[idx].toInt() else 0
                    val d = if (down != null && idx < down.size) down[idx].toInt() else 0
                    (u + d).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
        }
    }

    private fun transcodeWavToM4a(
        wavFile: File,
        m4aFile: File,
        sampleRate: Int
    ): Boolean {
        val pcmPayloadLen = (wavFile.length() - 44L).coerceAtLeast(0L)
        if (pcmPayloadLen <= 0L) return false

        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val codec = runCatching { MediaCodec.createEncoderByType(mime) }.getOrNull() ?: return false
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false
        var wroteAnySample = false

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(m4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            val readChunk = ByteArray(2048)
            var ptsUs = 0L

            FileInputStream(wavFile).use { fis ->
                var toSkip = 44L
                while (toSkip > 0L) {
                    val skipped = fis.skip(toSkip)
                    if (skipped <= 0L) break
                    toSkip -= skipped
                }

                while (true) {
                    val bytesRead = fis.read(readChunk)
                    if (bytesRead <= 0) break

                    var offset = 0
                    while (offset < bytesRead) {
                        val inIdx = codec.dequeueInputBuffer(15_000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx) ?: break
                            inBuf.clear()
                            val chunkLen = min(inBuf.remaining(), bytesRead - offset)
                            inBuf.put(readChunk, offset, chunkLen)
                            codec.queueInputBuffer(inIdx, 0, chunkLen, ptsUs, 0)
                            val samples = chunkLen / 2
                            ptsUs += (samples * 1_000_000L) / sampleRate
                            offset += chunkLen
                        }
                        if (drainEncoderStep(codec, muxer, bufferInfo, trackIndex, muxerStarted) { newTrack ->
                                trackIndex = newTrack
                                muxerStarted = true
                            }
                        ) {
                            wroteAnySample = true
                        }
                    }
                }
            }

            var eosQueued = false
            for (attempt in 0 until 20) {
                val eosIdx = codec.dequeueInputBuffer(15_000)
                if (eosIdx >= 0) {
                    codec.queueInputBuffer(
                        eosIdx,
                        0,
                        0,
                        ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    eosQueued = true
                    break
                }
                if (drainEncoderStep(codec, muxer, bufferInfo, trackIndex, muxerStarted) { newTrack ->
                        trackIndex = newTrack
                        muxerStarted = true
                    }
                ) {
                    wroteAnySample = true
                }
            }

            if (!eosQueued) return false

            var drainTries = 0
            var eosSeen = false
            while (!eosSeen && drainTries < 80) {
                drainTries++
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 15_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0 && muxerStarted &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                            wroteAnySample = true
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eosSeen = true
                        }
                    }
                }
            }

            return muxerStarted && wroteAnySample
        } catch (_: Throwable) {
            return false
        } finally {
            runCatching { codec.stop(); codec.release() }
            runCatching {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            }
        }
    }

    private fun drainEncoderStep(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        currentTrackIndex: Int,
        currentlyStarted: Boolean,
        onMuxerStarted: (Int) -> Unit
    ): Boolean {
        var trackIndex = currentTrackIndex
        var started = currentlyStarted
        var wrote = false
        var outIdx = codec.dequeueOutputBuffer(bufferInfo, 2_000)
        while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!started) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                    onMuxerStarted(trackIndex)
                }
            } else if (outIdx >= 0) {
                val outBuf = codec.getOutputBuffer(outIdx)
                if (outBuf != null && bufferInfo.size > 0 && started &&
                    (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                    wrote = true
                }
                codec.releaseOutputBuffer(outIdx, false)
            }
            outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
        return wrote
    }

    private fun patchWavHeader(
        wavFile: File,
        totalAudioBytes: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = totalAudioBytes + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8L
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen.toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate.toInt())
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(totalAudioBytes.toInt())
        }.array()

        RandomAccessFile(wavFile, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }
}
