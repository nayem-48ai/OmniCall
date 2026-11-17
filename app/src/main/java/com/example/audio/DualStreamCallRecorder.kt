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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
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
 * Deliverable #4: Local Dual-Stream Call Recorder (Scoped Storage Compliant)
 * ------------------------------------------------------------------------
 * Activated manually via the in-call Record button.
 * - For SIP VoIP calls: mixes synchronized uplink + downlink 16kHz PCM frames from [AudioStreamTap].
 * - For native SIM calls: probes multiple AudioSources (VOICE_COMMUNICATION -> VOICE_RECOGNITION -> MIC -> DEFAULT)
 *   and sample rates to reliably capture audio on all Android OEMs.
 * - Encodes into AAC (.m4a) via MediaCodec+MediaMuxer with automatic fallback to uncompressed WAV (.wav)
 *   if a device AAC encoder refuses configuration, guaranteeing a playable saved recording.
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
    private var currentFormat: RecordingFormat = RecordingFormat.AAC_M4A
    private var currentSampleRate: Int = 16000
    private var currentFile: File? = null
    private var startedAtMs: Long = 0L

    @SuppressLint("MissingPermission")
    fun startRecording(
        callId: String,
        remoteHandle: String,
        displayName: String,
        sourceType: CallSourceType,
        sourceLabel: String,
        format: RecordingFormat
    ): Boolean {
        if (isRecording) return true

        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return false

        var detectedSampleRate = 16000
        if (sourceType != CallSourceType.SIP) {
            val opened = openBestSimAudioRecord() ?: return false
            simAudioRecord = opened.first
            detectedSampleRate = opened.second
        }

        val dir = File(context.filesDir, "recordings").apply { if (!exists()) mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeParty = remoteHandle.replace(Regex("[^A-Za-z0-9+]"), "_").take(20).ifBlank { "call" }
        val fileName = "OmniCall_${safeParty}_$stamp.${format.extension}"
        val outFile = File(dir, fileName)

        currentCallId = callId
        currentRemoteHandle = remoteHandle
        currentDisplayName = displayName.ifBlank { remoteHandle }
        currentSourceLabel = sourceLabel
        currentFormat = format
        currentSampleRate = detectedSampleRate
        currentFile = outFile
        startedAtMs = System.currentTimeMillis()
        isRecording = true
        uplinkQueue.clear()
        downlinkQueue.clear()

        if (sourceType == CallSourceType.SIP) {
            uplinkCollectorJob = scope.launch {
                audioStreamTap.uplinkFrames.collect { frame ->
                    if (isRecording) {
                        if (uplinkQueue.size > 50) uplinkQueue.poll()
                        uplinkQueue.offer(frame.pcm16)
                    }
                }
            }
            downlinkCollectorJob = scope.launch {
                audioStreamTap.downlinkFrames.collect { frame ->
                    if (isRecording) {
                        if (downlinkQueue.size > 50) downlinkQueue.poll()
                        downlinkQueue.offer(frame.pcm16)
                    }
                }
            }
        }

        recordingJob = scope.launch {
            if (format == RecordingFormat.WAV) {
                writeWavLoop(outFile, sourceType, detectedSampleRate)
            } else {
                val aacSucceeded = writeAacLoop(outFile, sourceType, detectedSampleRate)
                if (!aacSucceeded) {
                    runCatching { outFile.delete() }
                    val wavFile = File(dir, "OmniCall_${safeParty}_$stamp.wav")
                    currentFormat = RecordingFormat.WAV
                    currentFile = wavFile
                    // If recording already stopped before fallback started, write at least a short valid file
                    writeWavLoop(wavFile, sourceType, detectedSampleRate)
                }
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun openBestSimAudioRecord(): Pair<AudioRecord, Int>? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )
        val rates = listOf(16000, 44100, 48000, 8000)

        for (source in sources) {
            for (rate in rates) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue
                val bufBytes = minBuf.coerceAtLeast((rate / 50) * 4)
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
                        return rec to rate
                    }
                }
                runCatching { rec.release() }
            }
        }
        return null
    }

    suspend fun stopRecording(): RecordingItem? {
        if (!isRecording) return null
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

        val file = currentFile ?: return null
        if (!file.exists() || file.length() <= 44L) {
            runCatching { file.delete() }
            return null
        }

        val durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1000L)
        val item = RecordingItem(
            id = 0L,
            fileName = file.name,
            filePath = file.absolutePath,
            format = currentFormat,
            sampleRateHz = currentSampleRate,
            durationMs = durationMs,
            fileSizeBytes = file.length(),
            remoteHandle = currentRemoteHandle,
            remoteDisplayName = currentDisplayName,
            sourceLabel = currentSourceLabel,
            createdAtMs = startedAtMs
        )
        val insertedId = recordingRepository.saveRecording(item)
        eventBus.publish(
            OmniCallEvent.RecordingCompleted(currentCallId, file.absolutePath, durationMs)
        )
        return item.copy(id = insertedId)
    }

    private suspend fun writeWavLoop(
        outFile: File,
        sourceType: CallSourceType,
        sampleRate: Int
    ) {
        val frameSamples = (sampleRate / 50).coerceAtLeast(160) // 20ms frame
        val tempBuf = ShortArray(frameSamples)
        FileOutputStream(outFile).use { fos ->
            fos.write(ByteArray(44))
            var totalAudioBytes = 0L

            while (isRecording ||
                (sourceType == CallSourceType.SIP && (uplinkQueue.isNotEmpty() || downlinkQueue.isNotEmpty()))
            ) {
                val mixed = pullNextMixedFrame(sourceType, tempBuf, frameSamples)
                if (mixed == null) {
                    delay(20)
                    continue
                }
                val bytes = ByteArray(mixed.size * 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mixed)
                fos.write(bytes)
                totalAudioBytes += bytes.size
            }
            fos.flush()
            patchWavHeader(outFile, totalAudioBytes, sampleRate, 1, 16)
        }
    }

    private suspend fun writeAacLoop(
        outFile: File,
        sourceType: CallSourceType,
        sampleRate: Int
    ): Boolean {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val codec = runCatching { MediaCodec.createEncoderByType(mime) }.getOrNull() ?: return false
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false
        var encodedAnySample = false

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            val frameSamples = (sampleRate / 50).coerceAtLeast(160)
            val tempBuf = ShortArray(frameSamples)
            var presentationTimeUs = 0L
            var silenceTicks = 0

            while (isRecording ||
                (sourceType == CallSourceType.SIP && (uplinkQueue.isNotEmpty() || downlinkQueue.isNotEmpty()))
            ) {
                val mixed = pullNextMixedFrame(sourceType, tempBuf, frameSamples)
                if (mixed != null) {
                    silenceTicks = 0
                    presentationTimeUs = queuePcmChunked(codec, mixed, sampleRate, presentationTimeUs)
                } else {
                    // Feed periodic 20ms silence during transient pauses so muxer cleanly starts even on short clips
                    silenceTicks++
                    if (silenceTicks >= 2 && !muxerStarted && isRecording) {
                        val silence = ShortArray(frameSamples)
                        presentationTimeUs = queuePcmChunked(codec, silence, sampleRate, presentationTimeUs)
                    }
                    delay(20)
                }

                val wrote = drainEncoder(codec, muxer, bufferInfo, trackIndex, muxerStarted) { newTrackIdx ->
                    trackIndex = newTrackIdx
                    muxerStarted = true
                }
                if (wrote) encodedAnySample = true
            }

            // Guarantee at least one frame queued before EOS if call ended instantly
            if (!encodedAnySample) {
                val pad = ShortArray(frameSamples * 3)
                presentationTimeUs = queuePcmChunked(codec, pad, sampleRate, presentationTimeUs)
            }

            val eosIdx = codec.dequeueInputBuffer(15_000)
            if (eosIdx >= 0) {
                codec.queueInputBuffer(eosIdx, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }

            var drainTries = 0
            var drainDone = false
            while (!drainDone && drainTries < 40) {
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
                            encodedAnySample = true
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            drainDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (encodedAnySample) drainDone = true else delay(10)
                    }
                }
            }
            return muxerStarted && encodedAnySample
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

    private fun queuePcmChunked(
        codec: MediaCodec,
        pcm: ShortArray,
        sampleRate: Int,
        startPtsUs: Long
    ): Long {
        var pts = startPtsUs
        val totalBytes = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(totalBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        var offset = 0
        while (offset < totalBytes.size) {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx < 0) break
            val inBuf = codec.getInputBuffer(inIdx) ?: break
            inBuf.clear()
            val chunkLen = min(inBuf.remaining(), totalBytes.size - offset)
            inBuf.put(totalBytes, offset, chunkLen)
            codec.queueInputBuffer(inIdx, 0, chunkLen, pts, 0)
            val chunkSamples = chunkLen / 2
            pts += (chunkSamples * 1_000_000L) / sampleRate
            offset += chunkLen
        }
        return pts
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        currentTrackIndex: Int,
        currentlyStarted: Boolean,
        onMuxerStarted: (Int) -> Unit
    ): Boolean {
        var trackIndex = currentTrackIndex
        var started = currentlyStarted
        var wroteSample = false
        var outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
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
                    wroteSample = true
                }
                codec.releaseOutputBuffer(outIdx, false)
            }
            outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
        return wroteSample
    }

    private suspend fun pullNextMixedFrame(
        sourceType: CallSourceType,
        tempBuf: ShortArray,
        frameSamples: Int
    ): ShortArray? {
        return if (sourceType == CallSourceType.SIP) {
            if (uplinkQueue.isEmpty() && downlinkQueue.isEmpty()) return null
            // Pace strictly to 20ms per mixed frame when only one side is present
            delay(20)
            val up = uplinkQueue.poll()
            val down = downlinkQueue.poll()
            if (up == null && down == null) return null
            val len = maxOf(up?.size ?: 0, down?.size ?: 0).coerceAtLeast(frameSamples)
            ShortArray(len) { idx ->
                val u = if (up != null && idx < up.size) up[idx].toInt() else 0
                val d = if (down != null && idx < down.size) down[idx].toInt() else 0
                (u + d).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        } else {
            val rec = simAudioRecord ?: return null
            val read = runCatching { rec.read(tempBuf, 0, tempBuf.size) }.getOrDefault(-1)
            if (read > 0) tempBuf.copyOf(read) else null
        }
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
