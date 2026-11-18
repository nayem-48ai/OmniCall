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
import androidx.core.content.ContextCompat
import com.example.data.repository.RecordingItem
import com.example.data.repository.RecordingRepository
import com.example.domain.ai.AudioStreamTap
import com.example.domain.ai.CallEventBus
import com.example.domain.ai.OmniCallEvent
import com.example.domain.model.CallSourceType
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
import kotlin.math.min

/**
 * Local Call Recorder (Scoped Storage Compliant)
 * ----------------------------------------------
 * Activated manually via the in-call Record button.
 *
 * Reliability architecture:
 * - Captures live 16-bit PCM directly into a WAV stream on [Dispatchers.IO], preventing frame loss
 *   or empty files on strict OEM MediaCodec implementations.
 * - Supports 3 [RecordingType] modes:
 *   1. [RecordingType.INTERNAL_AND_EXTERNAL]: SIP mixes uplink+downlink; SIM probes VOICE_CALL -> VOICE_COMMUNICATION -> VOICE_RECOGNITION -> MIC.
 *   2. [RecordingType.ONLY_INTERNAL]: SIP captures downlink only; SIM prioritizes VOICE_DOWNLINK -> VOICE_CALL -> fallbacks.
 *   3. [RecordingType.ONLY_EXTERNAL]: SIP captures uplink only; SIM prioritizes VOICE_UPLINK -> MIC -> VOICE_RECOGNITION.
 * - On stop, if [RecordingFormat.AAC_M4A] is chosen, encodes the captured PCM to `.m4a` via
 *   MediaCodec+MediaMuxer and verifies output integrity, seamlessly keeping the `.wav` if an OEM
 *   hardware AAC encoder declines configuration.
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

    private val uplinkQueue = ConcurrentLinkedQueue<ShortArray>()
    private val downlinkQueue = ConcurrentLinkedQueue<ShortArray>()

    @Volatile
    var isRecording: Boolean = false
        private set

    private var currentCallId: String = ""
    private var currentRemoteHandle: String = ""
    private var currentDisplayName: String = ""
    private var currentSourceLabel: String = ""
    private var currentRequestedFormat: RecordingFormat = RecordingFormat.AAC_M4A
    private var currentRecordingType: RecordingType = RecordingType.INTERNAL_AND_EXTERNAL
    private var currentSampleRate: Int = 16000
    private var currentWavFile: File? = null
    private var currentTargetM4aFile: File? = null
    private var startedAtMs: Long = 0L

    @SuppressLint("MissingPermission")
    suspend fun startRecording(
        callId: String,
        remoteHandle: String,
        displayName: String,
        sourceType: CallSourceType,
        sourceLabel: String,
        format: RecordingFormat,
        recordingType: RecordingType = RecordingType.INTERNAL_AND_EXTERNAL
    ): Boolean = withContext(Dispatchers.IO) {
        if (isRecording) return@withContext true

        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return@withContext false

        val candidateSources = buildAudioSourceCandidates(recordingType)
        var detectedSampleRate = 16000
        var activeSourceIndex = 0

        if (sourceType != CallSourceType.SIP) {
            val opened = openBestSimAudioRecord(candidateSources, startFromIndex = 0)
                ?: return@withContext false
            simAudioRecord = opened.recorder
            detectedSampleRate = opened.sampleRate
            activeSourceIndex = opened.sourceIndex
        }

        val dir = File(context.filesDir, "recordings").apply { if (!exists()) mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeParty = remoteHandle.replace(Regex("[^A-Za-z0-9+]"), "_").take(20).ifBlank { "call" }

        val wavFile = File(dir, "OmniCall_${safeParty}_$stamp.wav")
        val m4aFile = if (format == RecordingFormat.AAC_M4A) {
            File(dir, "OmniCall_${safeParty}_$stamp.m4a")
        } else {
            null
        }

        currentCallId = callId
        currentRemoteHandle = remoteHandle
        currentDisplayName = displayName.ifBlank { remoteHandle }
        currentSourceLabel = "$sourceLabel • ${recordingType.shortLabel}"
        currentRequestedFormat = format
        currentRecordingType = recordingType
        currentSampleRate = detectedSampleRate
        currentWavFile = wavFile
        currentTargetM4aFile = m4aFile
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
                            if (uplinkQueue.size > 64) uplinkQueue.poll()
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
                            if (downlinkQueue.size > 64) downlinkQueue.poll()
                            downlinkQueue.offer(frame.pcm16)
                        }
                    }
                }
            }
        }

        recordingJob = scope.launch {
            writeLiveWavStream(
                outFile = wavFile,
                sourceType = sourceType,
                recordingType = recordingType,
                initialSampleRate = detectedSampleRate,
                candidateSources = candidateSources,
                initialSourceIndex = activeSourceIndex
            )
        }
        true
    }

    private fun buildAudioSourceCandidates(recordingType: RecordingType): List<Int> {
        return when (recordingType) {
            RecordingType.INTERNAL_AND_EXTERNAL -> listOf(
                MediaRecorder.AudioSource.VOICE_CALL,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )
            RecordingType.ONLY_INTERNAL -> listOf(
                MediaRecorder.AudioSource.VOICE_DOWNLINK,
                MediaRecorder.AudioSource.VOICE_CALL,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
            RecordingType.ONLY_EXTERNAL -> listOf(
                MediaRecorder.AudioSource.VOICE_UPLINK,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.DEFAULT
            )
        }
    }

    private data class OpenedSimRecorder(
        val recorder: AudioRecord,
        val sampleRate: Int,
        val sourceIndex: Int
    )

    @SuppressLint("MissingPermission")
    private fun openBestSimAudioRecord(
        sources: List<Int>,
        startFromIndex: Int
    ): OpenedSimRecorder? {
        val rates = listOf(16000, 44100, 48000, 8000)

        for (srcIdx in startFromIndex until sources.size) {
            val source = sources[srcIdx]
            for (rate in rates) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue
                val bufBytes = minBuf.coerceAtLeast((rate / 50) * 6)
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
                        // Verify the audio HAL allows reading from this AudioSource
                        val probeBuf = ShortArray((rate / 50).coerceAtLeast(160))
                        val probeRead = runCatching {
                            rec.read(probeBuf, 0, probeBuf.size, AudioRecord.READ_BLOCKING)
                        }.getOrDefault(-1)
                        if (probeRead >= 0) {
                            return OpenedSimRecorder(rec, rate, srcIdx)
                        }
                    }
                }
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
        }
        return null
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

        val durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1000L)
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
        initialSampleRate: Int,
        candidateSources: List<Int>,
        initialSourceIndex: Int
    ) {
        var sampleRate = initialSampleRate
        var sourceIndex = initialSourceIndex
        var frameSamples = (sampleRate / 50).coerceAtLeast(160) // 20ms frame
        var simBuf = ShortArray(frameSamples)
        var consecutiveSimFailures = 0

        FileOutputStream(outFile).use { fos ->
            fos.write(ByteArray(44))
            var totalAudioBytes = 0L

            while (isRecording ||
                (sourceType == CallSourceType.SIP && (uplinkQueue.isNotEmpty() || downlinkQueue.isNotEmpty()))
            ) {
                val pcmFrame: ShortArray = if (sourceType == CallSourceType.SIP) {
                    val pulled = pullNextSipFrame(recordingType, frameSamples)
                    if (pulled != null) {
                        if (isRecording) delay(20)
                        pulled
                    } else {
                        delay(20)
                        if (!isRecording) break
                        // Write silence tick so call timeline and output file remain valid
                        ShortArray(frameSamples)
                    }
                } else {
                    val rec = simAudioRecord
                    if (rec == null) {
                        delay(20)
                        if (!isRecording) break
                        ShortArray(frameSamples)
                    } else {
                        val read = runCatching {
                            rec.read(simBuf, 0, simBuf.size, AudioRecord.READ_BLOCKING)
                        }.getOrDefault(-1)
                        if (read > 0) {
                            consecutiveSimFailures = 0
                            simBuf.copyOf(read)
                        } else {
                            consecutiveSimFailures++
                            if (consecutiveSimFailures >= 15 && sourceIndex + 1 < candidateSources.size) {
                                // Failover cleanly to next OEM-supported audio source
                                runCatching {
                                    rec.stop()
                                    rec.release()
                                }
                                val reopened = openBestSimAudioRecord(candidateSources, sourceIndex + 1)
                                if (reopened != null) {
                                    simAudioRecord = reopened.recorder
                                    sourceIndex = reopened.sourceIndex
                                    // Keep sample rate consistent with WAV header unless 0 bytes written yet
                                    if (totalAudioBytes == 0L && reopened.sampleRate != sampleRate) {
                                        sampleRate = reopened.sampleRate
                                        currentSampleRate = sampleRate
                                        frameSamples = (sampleRate / 50).coerceAtLeast(160)
                                        simBuf = ShortArray(frameSamples)
                                    }
                                    consecutiveSimFailures = 0
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
            val readChunk = ByteArray(2048) // 1024 mono samples
            var ptsUs = 0L

            FileInputStream(wavFile).use { fis ->
                // Skip 44-byte WAV header
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

            // Signal End Of Stream
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
