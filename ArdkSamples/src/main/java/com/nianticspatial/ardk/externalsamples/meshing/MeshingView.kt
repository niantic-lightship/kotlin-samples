package com.nianticspatial.ardk.externalsamples.meshing

import android.opengl.Matrix
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import com.nianticlabs.ardk.ARDKSession
import com.nianticlabs.ardk.MeshData
import com.nianticspatial.ardk.externalsamples.HelpContent
import com.nianticspatial.ardk.externalsamples.LocalSceneEngine
import com.nianticspatial.ardk.externalsamples.LocalSceneMaterialLoader
import kotlinx.serialization.Serializable
import com.nianticspatial.ardk.externalsamples.MeshRenderer
import com.nianticspatial.ardk.externalsamples.MeshRenderer.RenderableMeshChunk
import com.nianticspatial.ardk.externalsamples.addChildNode
import com.nianticspatial.ardk.externalsamples.meshing.MeshingManager.Companion.meshingRenderDelayMs
import io.github.sceneview.ar.node.PoseNode
import kotlin.collections.forEach

@Serializable
object MeshingRoute

@OptIn(DelicateCoroutinesApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MeshingView(ardkSession: ARDKSession, helpContentState: MutableState<HelpContent?>) {

    // Get the scene engine and material loader from ARSceneView
    val engine = LocalSceneEngine.current
    val materialLoader = LocalSceneMaterialLoader.current

    val meshingManager = remember { MeshingManager(ardkSession) }
    val meshRenderer = remember { MeshRenderer(engine, materialLoader) }

    var rootAnchorNode by remember { mutableStateOf<PoseNode?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val onScreenLogs = remember { mutableStateListOf<String>() }
    val maxLogLines = 100
    val listState = rememberLazyListState()

    // Set Help contents
    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text = "Meshing Sample Help\n\nMeshing sample Help\n\n" +
                    "This sample creates a 3d mesh of the environment using your device camera.\n\n" +
                    "TO USE:\nPress the \"Start Meshing\" button and look around using your device camera. " +
                    "A mesh is dynamically created and overlaid on top of your environment. This mesh is not persistent. " +
                    "To clear this mesh and restart the process, press the \"Stop Meshing\" button.",
                color = Color.White
            )
        }
        onDispose {
            helpContentState.value = null
            coroutineScope.cancel()
        }
    }

    // Let the manager handle cleaning itself up
    lifecycleOwner.lifecycle.addObserver(meshingManager)

    meshRenderer.setDefaultMaterialColor(Color(0f, 1f, 1f, 0.7f))

    // Function to receive meshingManger's toast messages and display them in a scrolling list
    fun addOnScreenLogMessage(message: String) {
        if (onScreenLogs.size >= maxLogLines) {
            onScreenLogs.removeAt(0)
        }
        onScreenLogs.add(message)

        // Animate scroll to the last item
        if (onScreenLogs.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(index = onScreenLogs.size - 1)
            }
        }
    }

    LaunchedEffect(meshingManager) {
        // Used for receiving and displaying toast messages
        meshingManager.toasts.collect { message ->
            addOnScreenLogMessage(message)
        }
    }

    // Process the meshData and pass it to the meshRenderer
    fun setLiveMeshChunks(meshChunksData: Collection<MeshData>) {
        val meshChunks = mutableListOf<RenderableMeshChunk>()

        if (meshChunksData.isNullOrEmpty()) {
            Log.d("MeshRenderer", "setDownloadedMeshChunks(): No mesh chunks provided.")
            meshRenderer.setRenderableMeshChunks(meshChunks)
            return
        }

        val identityModelMatrix = FloatArray(16)
        Matrix.setIdentityM(identityModelMatrix, 0)

        meshChunksData.forEach { mesh ->
            val renderableChunk = RenderableMeshChunk(
                meshData = mesh,
                modelMatrix = identityModelMatrix
            )
            meshChunks.add(renderableChunk)
        }

        meshRenderer.setRenderableMeshChunks(meshChunks)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Set up the UI to display log messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 8.dp, bottom = 100.dp)
                .fillMaxWidth(0.7f)
                .heightIn(max = 150.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(4.dp)
        ) {
            items(onScreenLogs) { logMessage ->
                Text(
                    text = logMessage,
                    color = Color.White,
                    fontSize = 10.sp
                )
            }
        }

        Button(
            onClick = {
                if (!meshingManager.meshingStarted) {

                    // We need a root node to attach the mesh to. If one does not exist, create it first
                    if (rootAnchorNode == null) {
                        rootAnchorNode = PoseNode(engine = engine)
                        addChildNode(rootAnchorNode!!)
                    }

                    // Start meshing, and set the callback for updating the mesh chunks
                    meshingManager.startMeshing() { meshIdToMeshData ->
                        setLiveMeshChunks(meshIdToMeshData.values)
                    }

                    // Coroutine to monitor for when we need to refresh the visible mesh
                    coroutineScope.launch {
                        while (meshingManager.meshingStarted) {
                            delay(meshingRenderDelayMs)

                            if (meshRenderer.needsFullMeshReload) {
                                rootAnchorNode?.let { node ->
                                    node.clearChildNodes()
                                    meshRenderer.createMeshNodes(node)
                                }
                            }
                        }
                    }
                } else {
                    // Stop meshing, and delete the old mesh
                    meshingManager.stopMeshing()
                    setLiveMeshChunks(emptyList())

                    rootAnchorNode?.let { node ->
                        node.clearChildNodes()
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(if (meshingManager.meshingStarted) "Stop Meshing" else "Start Meshing")
        }
    }
}
