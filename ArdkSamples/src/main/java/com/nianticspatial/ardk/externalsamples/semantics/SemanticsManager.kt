package com.nianticspatial.ardk.externalsamples.semantics

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.nianticlabs.ardk.ARDKResult
import com.nianticlabs.ardk.AwarenessFeatureMode
import com.nianticlabs.ardk.AwarenessStatus
import com.nianticlabs.ardk.awareness.semantics.SemanticsConfig
import com.nianticlabs.ardk.awareness.semantics.SemanticsResult
import com.nianticlabs.ardk.awareness.semantics.SemanticsSession
import com.nianticspatial.ardk.externalsamples.ARDKSessionManager
import com.nianticspatial.ardk.externalsamples.FeatureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transient, non-fatal issues that the UI can surface without stopping the session.
 */
sealed interface SemanticsWarningEvent {
    data class ChannelQueryFailed(val cause: Throwable) : SemanticsWarningEvent
    data class ChannelQueryError(val status: AwarenessStatus) : SemanticsWarningEvent
    data object ChannelsUnavailable : SemanticsWarningEvent
    data class LatestConfidenceQueryFailed(val channelIndex: Int, val cause: Throwable) :
        SemanticsWarningEvent

    data class LatestConfidenceResultError(val channelIndex: Int, val status: AwarenessStatus) :
        SemanticsWarningEvent

    data class StopFailed(val cause: Throwable) : SemanticsWarningEvent
    data object Cleared : SemanticsWarningEvent
}

/**
 * Long-lived lifecycle description for the semantics session.
 */
sealed interface SemanticsSessionState {
    data object Idle : SemanticsSessionState
    data object LoadingChannels : SemanticsSessionState
    data class Streaming(val channelName: String?) : SemanticsSessionState
    data object Stopping : SemanticsSessionState
    data class Failed(val cause: Throwable?) : SemanticsSessionState
}

class SemanticsManager(
    ardkSessionManager: ARDKSessionManager,
    parentScope: CoroutineScope? = null,
) : FeatureManager() {

    companion object {
        private const val TAG = "SemanticsManager"
        private const val CHANNEL_POLL_DELAY_MS = 100L
        private const val RESULT_POLL_DELAY_MS = 16L
        private const val MAX_CHANNEL_POLLS = 1000
        private const val DEFAULT_ALPHA = 0.5f
    }

    private val semanticsSession: SemanticsSession = ardkSessionManager.session.semantics.acquire()

    private val scope: CoroutineScope = parentScope?.let { parent ->
        val parentJob = parent.coroutineContext[Job]
        CoroutineScope(parent.coroutineContext + SupervisorJob(parentJob))
    } ?: CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var channelJob: Job? = null
    private var resultJob: Job? = null

    private val _channels = MutableStateFlow<List<String>>(emptyList())
    val channels: StateFlow<List<String>> = _channels.asStateFlow()

    private val _selectedChannelIndex = MutableStateFlow(-1)
    val selectedChannelIndex: StateFlow<Int> = _selectedChannelIndex.asStateFlow()

    private val _sessionState =
        MutableStateFlow<SemanticsSessionState>(SemanticsSessionState.Idle)
    val sessionState: StateFlow<SemanticsSessionState> = _sessionState.asStateFlow()

    private val _warnings = MutableSharedFlow<SemanticsWarningEvent>(replay = 0)
    val warnings: SharedFlow<SemanticsWarningEvent> = _warnings.asSharedFlow()

    private val _latestResult = MutableStateFlow<SemanticsResult?>(null)
    val latestResult: StateFlow<SemanticsResult?> = _latestResult.asStateFlow()

    private val _overlayOpacity = MutableStateFlow(DEFAULT_ALPHA)
    val overlayOpacity: StateFlow<Float> = _overlayOpacity.asStateFlow()

    private var isPaused = false

    suspend fun start() {
        val current = _sessionState.value
        if (
            current is SemanticsSessionState.LoadingChannels ||
            current is SemanticsSessionState.Streaming ||
            current is SemanticsSessionState.Stopping
        ) {
            return
        }

        _sessionState.value = SemanticsSessionState.LoadingChannels

        runCatching { configureSession() }
            .onFailure { error ->
                Log.e(TAG, "Failed to configure semantics", error)
                _sessionState.value = SemanticsSessionState.Failed(error)
                return
            }

        runCatching {
            withContext(Dispatchers.Default) {
                semanticsSession.start()
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to start semantics", error)
            _sessionState.value = SemanticsSessionState.Failed(error)
            return
        }

        pollChannels()
    }

    fun stop() {
        when (_sessionState.value) {
            is SemanticsSessionState.Idle,
            is SemanticsSessionState.Failed,
            SemanticsSessionState.Stopping -> return
            else -> Unit
        }

        _sessionState.value = SemanticsSessionState.Stopping

        channelJob?.cancel()
        channelJob = null
        resultJob?.cancel()
        resultJob = null

        runCatching {
            semanticsSession.stop()
        }.onFailure { error ->
            Log.e(TAG, "Failed to stop semantics", error)
            emitWarning(SemanticsWarningEvent.StopFailed(error))
        }

        _channels.value = emptyList()
        _selectedChannelIndex.value = -1
        _latestResult.value = null
        _sessionState.value = SemanticsSessionState.Idle
    }

    fun selectChannel(index: Int) {
        if (index == _selectedChannelIndex.value) return
        if (index < 0 || index >= _channels.value.size) return

        _selectedChannelIndex.value = index
        _latestResult.value = null
        updateStreamingState()
    }

    fun setOverlayOpacity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _overlayOpacity.value = clamped
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        val running = isSessionRunning()
        isPaused = running
        if (running) {
            stop()
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        if (isPaused) {
            scope.launch {
                start()
            }
            isPaused = false
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        stop()
        scope.cancel()
        runCatching { semanticsSession.close() }
            .onFailure { error -> Log.e(TAG, "Failed to close semantics session", error) }
    }

    private suspend fun configureSession() {
        withContext(Dispatchers.Default) {
            val config = SemanticsConfig().apply {
                frameRate = 20
                mode = AwarenessFeatureMode.UNSPECIFIED
            }
            semanticsSession.configure(config)
        }
    }

    private fun pollChannels() {
        channelJob?.cancel()
        channelJob = scope.launch(Dispatchers.Default) {
            var polls = 0
            while (isActive && isSessionRunning() && polls < MAX_CHANNEL_POLLS) {
                val result = runCatching { semanticsSession.channelNames() }
                    .onFailure { error ->
                        Log.e(TAG, "Channel query failed", error)
                        emitWarning(SemanticsWarningEvent.ChannelQueryFailed(error))
                    }
                    .getOrNull()
                val channelNames = when (result) {
                    is ARDKResult.Success -> result.value.toList()
                    is ARDKResult.Error -> {
                        Log.e(TAG, "Channel query error: ${result.code}")
                        emitWarning(SemanticsWarningEvent.ChannelQueryError(result.code))
                        emptyList()
                    }
                    else -> emptyList()
                }

                if (channelNames.isNotEmpty()) {
                    withContext(Dispatchers.Main.immediate) {
                        _channels.value = channelNames
                        val defaultIndex =
                            channelNames.indexOfFirst { it.contains("ground", ignoreCase = true) }
                        _selectedChannelIndex.value = if (defaultIndex >= 0) defaultIndex else 0
                        updateStreamingState()
                    }
                    clearWarning()
                    startResultPolling()
                    return@launch
                }

                polls++
                delay(CHANNEL_POLL_DELAY_MS)
            }

            if (_channels.value.isEmpty()) {
                emitWarning(SemanticsWarningEvent.ChannelsUnavailable)
            }
        }
    }

    private fun startResultPolling() {
        resultJob?.cancel()
        resultJob = scope.launch(Dispatchers.Default) {
            var lastTimestamp = 0L
            var previousIndex = -1
            while (isActive && isSessionRunning()) {
                val index = _selectedChannelIndex.value
                if (index != previousIndex) {
                    previousIndex = index
                    lastTimestamp = 0L
                }
                if (index >= 0) {
                    val result = try {
                        semanticsSession.latestConfidence(index)
                    } catch (error: Exception) {
                        Log.e(TAG, "latestConfidence threw", error)
                        emitWarning(
                            SemanticsWarningEvent.LatestConfidenceQueryFailed(index, error)
                        )
                        delay(RESULT_POLL_DELAY_MS)
                        continue
                    }
                    when (result) {
                        is ARDKResult.Success -> {
                            val confidence = result.value
                            if (confidence.timestampMs > lastTimestamp) {
                                lastTimestamp = confidence.timestampMs
                                withContext(Dispatchers.Main.immediate) {
                                    _latestResult.value = confidence
                                }
                                clearWarning()
                            }
                        }
                        is ARDKResult.Error -> {
                            Log.e(TAG, "Semantics error: ${result.code}")
                            emitWarning(
                                SemanticsWarningEvent.LatestConfidenceResultError(
                                    index,
                                    result.code
                                )
                            )
                        }
                    }
                }
                delay(RESULT_POLL_DELAY_MS)
            }
        }
    }

    private fun updateStreamingState() {
        if (!isSessionRunning()) return
        val channelName = _channels.value.getOrNull(_selectedChannelIndex.value)
        _sessionState.value = SemanticsSessionState.Streaming(channelName)
    }

    private fun emitWarning(event: SemanticsWarningEvent) {
        if (!_warnings.tryEmit(event)) {
            scope.launch { _warnings.emit(event) }
        }
    }

    private fun clearWarning() {
        emitWarning(SemanticsWarningEvent.Cleared)
    }

    private fun isSessionRunning(): Boolean =
        when (_sessionState.value) {
            SemanticsSessionState.LoadingChannels,
            is SemanticsSessionState.Streaming -> true
            else -> false
        }
}
