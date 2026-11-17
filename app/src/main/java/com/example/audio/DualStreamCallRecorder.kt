package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
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

/**
 * Deliverable #4: Local Dual-Stream Call Recorder (Scoped Storage Compliant)
 * ------------------------------------------------------------------------
 * Activated manually via the in-call Record button.
 * - For SIP VoIP calls: mixes synchronized uplink + downlink 16kHz PCM frames from [AudioStreamTap].
 * - For native SIM calls: captures via [MediaRecorder.AudioSource.VOICE_COMMUNICATION] / MIC fallback.
 * - Encodes directly into AAC (.m4a) via hardware MediaCodec+MediaMuxer or uncompressed WAV (.wav).
 * - Saves inside app-private scoped storage (`filesDir/recordings`) with FileProvider export support.
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
        currentFile = outFile
        startedAtMs = System.currentTimeMillis()
        isRecording = true
        uplinkQueue.clear()
        downlinkQueue.clear()

        if (sourceType == CallSourceType.SIP) {
            uplinkCollectorJob = scope.launch {
                audioStreamTap.uplinkFrames.collect { frame ->
                    if (isRecording) uplinkQueue.offer(frame.pcm16)
                }
            }
            downlinkCollectorJob = scope.launch {
                audioStreamTap.downlinkFrames.collect { frame ->
                    if (isRecording) downlinkQueue.offer(frame.pcm16)
                }
            }
        } else {
            // Cellular SIM fallback mic capture
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2560)
            simAudioRecord = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                ).also {
                    if (it.state == AudioRecord.STATE_INITIALIZED) it.startRecording()
                }
            }.getOrNull()
        }

        recordingJob = scope.launch {
            if (format == RecordingFormat.WAV) {
                writeWavLoop(outFile, sourceType)
            } else {
                writeAacLoop(outFile, sourceType)
            }
        }
        return true
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
        if (!file.exists() || file.length() == 0L) return null

        val durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1000L)
        val item = RecordingItem(
            id = 0L,
            fileName = file.name,
            filePath = file.absolutePath,
            format = currentFormat,
            sampleRateHz = 16000,
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

    private suspend fun writeWavLoop(outFile: File, sourceType: CallSourceType) {
        val sampleRate = 16000
        FileOutputStream(outFile).use { fos ->
            // Write placeholder 44-byte RIFF WAV header
            fos.write(ByteArray(44))
            var totalAudioBytes = 0L
            val tempBuf = ShortArray(320)

            while (isRecording || uplinkQueue.isNotEmpty() || downlinkQueue.isNotEmpty()) {
                val mixed = pullNextMixedFrame(sourceType, tempBuf)
                if (mixed == null) {
                    delay(15)
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

    private suspend fun writeAacLoop(outFile: File, sourceType: CallSourceType) {
        val sampleRate = 16000
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }

        val codec = runCatching { MediaCodec.createEncoderByType(mime) }.getOrNull()
        if (codec == null) {
            writeWavLoop(outFile, sourceType)
            return
        }

        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            val tempBuf = ShortArray(320)
            var presentationTimeUs = 0L

            while (isRecording || uplinkQueue.isNotEmpty() || downlinkQueue.isNotEmpty()) {
                val mixed = pullNextMixedFrame(sourceType, tempBuf)
                if (mixed != null) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        inBuf?.clear()
                        val byteCount = mixed.size * 2
                        val rawBytes = ByteArray(byteCount)
                        ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mixed)
                        inBuf?.put(rawBytes)
                        codec.queueInputBuffer(inIdx, 0, byteCount, presentationTimeUs, 0)
                        presentationTimeUs += (mixed.size * 1_000_000L) / sampleRate
                    }
                } else {
                    delay(15)
                }

                var outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
                while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0 && muxerStarted) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            // Signal End of Stream
            val eosIdx = codec.dequeueInputBuffer(10_000)
            if (eosIdx >= 0) {
                codec.queueInputBuffer(eosIdx, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            var drainDone = false
            while (!drainDone) {
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0 && muxerStarted &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) drainDone = true
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    drainDone = true
                }
            }
        } finally {
            runCatching { codec.stop(); codec.release() }
            runCatching { if (muxerStarted) muxer?.stop(); muxer?.release() }
        }
    }

    private fun pullNextMixedFrame(sourceType: CallSourceType, tempBuf: ShortArray): ShortArray? {
        return if (sourceType == CallSourceType.SIP) {
            val up = uplinkQueue.poll()
            val down = downlinkQueue.poll()
            if (up == null && down == null) return null
            val len = maxOf(up?.size ?: 0, down?.size ?: 0)
            ShortArray(len) { idx ->
                val u = if (up != null && idx < up.size) up[idx].toInt() else 0
                val d = if (down != null && idx < down.size) down[idx].toInt() else 0
                (u + d).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        } else {
            val rec = simAudioRecord ?: return null
            val read = rec.read(tempBuf, 0, tempBuf.size)
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
            putShort(1.toShort()) // PCM
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
