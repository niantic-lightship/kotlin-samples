package com.nianticspatial.ardk.externalsamples.depth

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.nianticlabs.ardk.ARDKResult
import com.nianticlabs.ardk.AwarenessFeatureMode
import com.nianticlabs.ardk.AwarenessStatus
import com.nianticlabs.ardk.DepthConfig
import com.nianticlabs.ardk.depth.DepthSession
import com.nianticlabs.ardk.DepthBuffer
import com.nianticspatial.ardk.externalsamples.FeatureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DepthManager(
    private val session: DepthSession,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : FeatureManager() {
    val TAG = "DepthManager"

    val logMessages = mutableStateListOf<String>()
    var isRunning by mutableStateOf(false)
        private set
    var currentDepthBuffer by mutableStateOf<DepthBuffer?>(null)
        private set

    private val depthSession: DepthSession get() = session
    private var pollJob: Job? = null

    val depthFrameRate = 30
    private var lastFrameTime: Long = 0L

    init {
        depthSession.configure(
          DepthConfig(
            framerate = depthFrameRate,
            featureMode = AwarenessFeatureMode.UNSPECIFIED
          )
        )
        log("Configured depth session")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        stop()
        try {
            depthSession.close()
            log("Closed depth session")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing depth session", e)
        }
        coroutineScope.cancel()
    }

    fun startDepth() {
        if (isRunning) return
        val status = depthSession.start()
        isRunning = true
        log("Started depth session")

        pollJob = coroutineScope.launch {
            var hasReceivedFirstFrame = false
            while (isRunning) {
                when (val latestDepth = depthSession.latestDepth()) {
                    is ARDKResult.Success -> {
                        hasReceivedFirstFrame = true
                        if (latestDepth.value.timestampMs > lastFrameTime) {
                            lastFrameTime = latestDepth.value.timestampMs
                            currentDepthBuffer = latestDepth.value
                        }
                    }
                    is ARDKResult.Error -> {
                        if (!hasReceivedFirstFrame || latestDepth.code == AwarenessStatus.NOT_READY) {
                            log("No depth frame received: ${latestDepth.code}")
                            log( "depth Status:  ${status}")
                            delay(500)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        pollJob?.cancel()
        pollJob = null
        depthSession.stop()
        isRunning = false
        currentDepthBuffer = null
        log("Stopped depth session")
    }


    private fun log(message: String) {
        Log.i(TAG, message)
        logMessages.add(message)
    }
}
