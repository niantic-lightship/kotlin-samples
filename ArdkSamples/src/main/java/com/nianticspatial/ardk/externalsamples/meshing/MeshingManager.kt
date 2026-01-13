package com.nianticspatial.ardk.externalsamples.meshing

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.nianticlabs.ardk.ArdkStatusException
import com.nianticlabs.ardk.MeshData
import com.nianticlabs.ardk.MeshingConfig
import com.nianticlabs.ardk.MeshingUpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nianticlabs.ardk.ARDKSession
import com.nianticspatial.ardk.externalsamples.FeatureManager

class MeshingManager(
    private val ardkSession: ARDKSession,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : FeatureManager() {

    companion object {
        val meshingUpdateDelayMs = 100L
        val meshingRenderDelayMs = 300L
        val meshDataHasUpdateFlag = 1.toByte()
    }

    val meshingSession = ardkSession.meshingSession.acquire()

    val meshIdToMeshData = mutableStateMapOf<Long, MeshData>()

    var meshingStarted by mutableStateOf(false)
        private set

    var lastUpdateTime by mutableLongStateOf(0)
        private set

    init {
        meshingSession.configure(
            MeshingConfig(
                fuseKeyframesOnly = true,
            )
        )
    }

    fun startMeshing(updateMeshCallback: (MutableMap<Long, MeshData>) -> Unit) {
        meshingStarted = true
        meshingSession.start()

        // Periodically poll the meshingSession for updated info, and pass that along to the View.
        // This will also post messages about the meshing update status that Views can listen for.
        coroutineScope.launch {
            while (meshingStarted) {
                delay(meshingUpdateDelayMs)
                val latestUpdateTime = meshingSession.getLastUpdateTime()
                if (latestUpdateTime != null) {
                    if (latestUpdateTime > lastUpdateTime) {
                        lastUpdateTime = latestUpdateTime
                        _toasts.emit("New Meshing Update Time: $latestUpdateTime")

                        val updatedMeshInfo = meshingSession.getUpdatedInfos()
                        if (updatedMeshInfo != null) {
                            Log.d("Meshing", "Updated Mesh Infos: ${updatedMeshInfo}")
                            val updateInfo: MeshingUpdateInfo =
                                updatedMeshInfo // Type is MeshingUpdateInfo
                            Log.d("Meshing", "Received MeshingUpdateInfo: $updateInfo")

                            // Iterate through the IDs provided in the updateInfo
                            updateInfo.ids.forEachIndexed { index, meshId ->
                                val isUpdated =
                                    (updateInfo.updated.getOrNull(index) == meshDataHasUpdateFlag)// Get corresponding updated flag, convert to bool
                                // Get the MeshData for this specific meshId if its updated
                                if (isUpdated) {
                                    try {
                                        val meshDataResult = meshingSession.getData(meshId)
                                        val meshData: MeshData = meshDataResult!!
                                        meshIdToMeshData[meshId] =
                                            meshData // Add/update in your state map
                                    } catch (e: ArdkStatusException) {
                                        Log.e("Meshing", "Exception getting MeshData for ID $meshId: $e")
                                    }
                                }
                            }

                            updateMeshCallback.invoke(meshIdToMeshData)
                        } else {
                            _toasts.emit("No mesh info available.")
                        }
                    } else {
                        _toasts.emit("Waiting for new meshing update")
                    }
                } else {
                    _toasts.emit("Mesh update time not yet available.")
                }
            }
        }
    }

    fun stopMeshing() {
        meshingStarted = false
        meshingSession.stop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopMeshing()
        coroutineScope.cancel()
        meshingSession.close()
    }
}


