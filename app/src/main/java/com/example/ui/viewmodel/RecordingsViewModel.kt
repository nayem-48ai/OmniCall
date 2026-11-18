package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.media.MediaPlayer
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.repository.RecordingItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackState(
    val activeRecordingId: Long? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0
)

data class RecordingsUiState(
    val recordings: List<RecordingItem> = emptyList(),
    val searchQuery: String = "",
    val playback: PlaybackState = PlaybackState()
)

class RecordingsViewModel(
    application: Application,
    private val container: AppContainer
) : AndroidViewModel(application) {

    private val _searchQuery = MutableStateFlow("")
    private val _playback = MutableStateFlow(PlaybackState())

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    val uiState: StateFlow<RecordingsUiState> = combine(
        container.recordingRepository.recordingsFlow,
        _searchQuery,
        _playback
    ) { list, query, playback ->
        val filtered = if (query.isBlank()) list else {
            val q = query.trim().lowercase()
            list.filter {
                it.remoteHandle.lowercase().contains(q) ||
                    it.remoteDisplayName.lowercase().contains(q) ||
                    it.sourceLabel.lowercase().contains(q)
            }
        }
        RecordingsUiState(
            recordings = filtered,
            searchQuery = query,
            playback = playback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RecordingsUiState()
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun togglePlayPause(item: RecordingItem) {
        val current = _playback.value
        if (current.activeRecordingId == item.id && mediaPlayer != null) {
            val mp = mediaPlayer!!
            if (mp.isPlaying) {
                mp.pause()
                _playback.update { it.copy(isPlaying = false) }
            } else {
                mp.start()
                _playback.update { it.copy(isPlaying = true) }
                startProgressTicker()
            }
            return
        }

        stopPlayback()
        val file = File(item.filePath)
        if (!file.exists()) return

        runCatching {
            val audioManager = getApplication<Application>().getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.mode = android.media.AudioManager.MODE_NORMAL
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    _playback.update {
                        it.copy(isPlaying = false, positionMs = 0)
                    }
                    progressJob?.cancel()
                }
                start()
            }
            mediaPlayer = mp
            _playback.value = PlaybackState(
                activeRecordingId = item.id,
                isPlaying = true,
                positionMs = 0,
                durationMs = mp.duration.coerceAtLeast(item.durationMs.toInt())
            )
            startProgressTicker()
        }
    }

    fun seekTo(positionMs: Int) {
        runCatching {
            mediaPlayer?.seekTo(positionMs)
            _playback.update { it.copy(positionMs = positionMs) }
        }
    }

    fun deleteRecording(item: RecordingItem) {
        if (_playback.value.activeRecordingId == item.id) {
            stopPlayback()
        }
        viewModelScope.launch {
            container.recordingRepository.deleteRecording(item)
        }
    }

    fun createShareIntent(item: RecordingItem): Intent? {
        val file = File(item.filePath)
        if (!file.exists()) return null
        val context = getApplication<Application>()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = if (item.format.extension == "wav") "audio/wav" else "audio/mp4"
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying) {
                    _playback.update { it.copy(positionMs = mp.currentPosition) }
                }
                delay(200L)
            }
        }
    }

    private fun stopPlayback() {
        progressJob?.cancel()
        progressJob = null
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        _playback.value = PlaybackState()
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }
}
