// Copyright 2025 Niantic.
package com.nianticspatial.ardk.externalsamples.vps

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nianticlabs.ardk.AnchorTrackingState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import com.nianticlabs.ardk.MeshDownloaderData
import com.nianticspatial.ardk.externalsamples.ARDKSessionManager
import com.nianticspatial.ardk.externalsamples.BuildConfig
import com.nianticspatial.ardk.externalsamples.HelpContent
import com.nianticspatial.ardk.externalsamples.*
import com.nianticspatial.ardk.externalsamples.common.ErrorDisplay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import io.github.sceneview.node.CubeNode
import io.github.sceneview.ar.node.PoseNode
import dev.romainguy.kotlin.math.Float3
import com.nianticlabs.ardk.UUID
import com.nianticspatial.ardk.externalsamples.MeshRenderer
import com.nianticspatial.ardk.externalsamples.MeshRenderer.RenderableMeshChunk
import kotlinx.coroutines.delay

@Serializable
data class VPSRoute(val payload: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VPSView(
    context: Context,
    ardkManager: ARDKSessionManager,
    helpContentState: MutableState<HelpContent?>,
    initialPayload: String? = null
) {
    // Get the scene engine and material loader from ARSceneView
    val engine = LocalSceneEngine.current
    val materialLoader = LocalSceneMaterialLoader.current

    val vpsManager = remember { VPSManager(ardkManager, ardkManager.session.vps.acquire()) }
    val meshDownloadManager = remember { MeshDownloadManager(ardkManager.session) }
    val meshRenderer = remember { MeshRenderer(engine, materialLoader) }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Set Help contents
    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text = "VPS Sample Help\n\nThis sample shows the VPS flow.\nTO USE:\n Begin by pressing the" +
                    " \"Search for nearby area targets\" this will prompt the VPS Coverage api to show potential " +
                    "location targets that you may localize to, tap on one to select it.\n Once a location target is " +
                    "selected, use the hint image to find the recommended localizable spot, and once you can point the " +
                    "camera at the target press the \"Start VPS\".\n Once you've localized, a mesh of the area will be " +
                    "downloaded and placed on top of the real life location.",
                color = Color.White
            )
        }
        onDispose { helpContentState.value = null }
    }

    // Let the manager handle cleaning itself up
    DisposableEffect(lifecycleOwner, vpsManager) {
        lifecycleOwner.lifecycle.addObserver(vpsManager)
        lifecycleOwner.lifecycle.addObserver(meshDownloadManager)
        onDispose {
            vpsManager.onDestroy(lifecycleOwner)
            meshDownloadManager.onDestroy(lifecycleOwner)
            lifecycleOwner.lifecycle.removeObserver(vpsManager)
            lifecycleOwner.lifecycle.removeObserver(meshDownloadManager)
        }
    }

    var dropdownExpanded by remember { mutableStateOf(false) }

    val meshDownloadStatus by meshDownloadManager.status.collectAsState()

    // Track which anchor IDs currently have nodes in the scene
    val localAnchorNodeMap = remember { mutableMapOf<UUID, PoseNode>() }
    var targetAnchorNode by remember { mutableStateOf<PoseNode?>(null) }
    var targetAnchorNodeId by remember { mutableStateOf<UUID?>(null) }

    // Create TRUE unlit material for VPS anchor cubes (no PBR/lighting costs!)
    val localAnchorMaterial = remember {
        createUnlitColorMaterial(context, materialLoader, Color(0xFF42FF44))
    }
    val targetAnchorMaterial = remember {
        createUnlitColorMaterial(context, materialLoader, Color(0xFF4285F4))
    }

    // Release the Filament material instances before the engine is torn down
    // (e.g., during an orientation change) to avoid Filament aborting because
    // “UnlitColor” still has a live instance.
    DisposableEffect(engine) {
        onDispose {
            // All cube nodes need to be destroyed before destroy the material instance
            // because they have the reference to the material instance.
            localAnchorNodeMap.values.forEach { localAnchorNode ->
                localAnchorNode.destroyRecursively(arChildNodes)
            }
            localAnchorNodeMap.clear()

            targetAnchorNode?.destroyRecursively(arChildNodes)
            targetAnchorNode = null
            targetAnchorNodeId = null

            engine.destroyMaterialInstance(localAnchorMaterial)
            engine.destroyMaterialInstance(targetAnchorMaterial)
        }
    }

    // Map of location name to VPS anchor payload.
    // TODO: Replace the dummy entry (and/or add additional entries) with your own VPS payload(s).
    val payloadOptions = mapOf(
        "Dummy Location" to BuildConfig.DEFAULT_VPS_PAYLOAD
    )

    var selectedLocation by remember { mutableStateOf(payloadOptions.keys.first()) }
    val selectedPayload: String = payloadOptions[selectedLocation] ?: ""

    val sessionState by vpsManager.sessionState.collectAsState()
    val isTracking = when (sessionState) {
        VpsSessionState.Ready,
        is VpsSessionState.Failed -> false

        else -> true
    }
    val targetAnchorState by vpsManager.targetAnchorState.collectAsState()
    val targetAnchorConfidence by vpsManager.targetAnchorConfidence.collectAsState()
    val targetAnchorId by vpsManager.targetAnchorId.collectAsState()
    val sessionId by vpsManager.sessionId.collectAsState()
    val geolocationInfo by vpsManager.geolocationInfo.collectAsState()
    val localAnchorPayloads by vpsManager.localAnchorPayloads.collectAsState()
    val localAnchorStates by vpsManager.localAnchorStatesFlow.collectAsState()

    var warningMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vpsManager) {
        vpsManager.warnings.collectLatest { event ->
            val message = when (event) {
                is VpsWarningEvent.AnchorTrackFailed ->
                    "Unable to track VPS anchor payload: ${event.payload}"

                is VpsWarningEvent.AnchorUpdateFailed ->
                    "Anchor update failed (${event.anchorId}): ${event.cause.localizedMessage.orEmpty()}"

                is VpsWarningEvent.AnchorPayloadFailed ->
                    "Anchor payload fetch failed (${event.anchorId}): ${event.cause.localizedMessage.orEmpty()}"

                is VpsWarningEvent.SessionIdQueryFailed ->
                    "Failed to query VPS session ID: ${event.cause.localizedMessage.orEmpty()}"

                is VpsWarningEvent.SessionStopFailed ->
                    "Failed to stop VPS session: ${event.cause.localizedMessage.orEmpty()}"
            }
            warningMessage = message
        }
    }

    // Update SceneView nodes based on anchor transforms from VPSManager
    LaunchedEffect(vpsManager, engine, materialLoader) {
        vpsManager.localAnchorPoses.collectLatest { poses ->
            // Remove nodes for anchors that are no longer tracked
            val anchorsToRemove = localAnchorNodeMap.keys.filter { it !in poses.keys }
            anchorsToRemove.forEach { anchorId ->
                localAnchorNodeMap[anchorId]?.destroyRecursively(arChildNodes)
                localAnchorNodeMap.remove(anchorId)
            }

            // Add or update nodes for tracked anchors
            poses.forEach { (anchorId, pose) ->
                val existingNode = localAnchorNodeMap[anchorId]
                if (existingNode == null) {
                    // Create new PoseNode with CubeNode child
                    val poseNode = PoseNode(engine = engine).apply {
                        // Create a cube as a child of this pose node with local anchor material
                        val cubeNode = CubeNode(
                            engine = engine,
                            size = Float3(0.1f, 0.1f, 0.1f), // Smaller cube
                            center = Float3(0f, 0f, 0f),
                            materialInstance = localAnchorMaterial
                        )
                        addChildNode(cubeNode)
                    }
                    poseNode.pose = pose

                    // Add to scene and track it
                    arChildNodes.add(poseNode)
                    localAnchorNodeMap[anchorId] = poseNode
                } else {
                    // Update existing node's transform
                    existingNode.pose = pose
                }
            }
        }
    }

    LaunchedEffect(vpsManager, engine, materialLoader) {
        vpsManager.targetAnchorId
            .combine(vpsManager.targetAnchorPose) { anchorId, pose -> anchorId to pose }
            .collectLatest { (anchorId, pose) ->
                val existingNode = targetAnchorNode

                if (anchorId == null || pose == null) {
                    existingNode?.destroyRecursively(arChildNodes)
                    targetAnchorNode = null
                    targetAnchorNodeId = null
                } else {
                    if (existingNode == null || targetAnchorNodeId != anchorId) {
                        existingNode?.destroyRecursively(arChildNodes)
                        val poseNode = PoseNode(engine = engine).apply {
                            val cubeNode = CubeNode(
                                engine = engine,
                                size = Float3(0.12f, 0.12f, 0.12f),
                                center = Float3(0f, 0f, 0f),
                                materialInstance = targetAnchorMaterial
                            )
                            addChildNode(cubeNode)
                        }
                        poseNode.pose = pose
                        arChildNodes.add(poseNode)
                        targetAnchorNode = poseNode
                        targetAnchorNodeId = anchorId
                        // Immediately attach create and attach downloaded mesh if already downloaded
                        if (meshDownloadStatus == MeshDownloadStatus.COMPLETED) {
                            targetAnchorNode?.let { node -> meshRenderer.createMeshNodes(node) }
                        }
                    } else {
                        existingNode.pose = pose
                    }
                }
            }
    }

    // Convert the downloaded data and pass it to the meshRenderer
    fun setDownloadedMeshChunks(meshChunksData: Array<MeshDownloaderData>?) {
        val meshChunks = mutableListOf<RenderableMeshChunk>()

        if (meshChunksData.isNullOrEmpty()) {
            Log.d("MeshRenderer", "setDownloadedMeshChunks(): No mesh chunks provided.")
            meshRenderer.setRenderableMeshChunks(meshChunks)
            return
        }

        meshChunksData.forEach { chunkData ->
            val renderableChunk = RenderableMeshChunk(
                meshData = chunkData.meshData,
                modelMatrix = chunkData.transform,
                textureData = chunkData.imageData,
            )
            meshChunks.add(renderableChunk)
        }

        meshRenderer.setRenderableMeshChunks(meshChunks)
    }

    // Update the mesh rendering if needed
    LaunchedEffect(Unit) {
        when (meshDownloadStatus) {
            MeshDownloadStatus.READY -> {
                meshDownloadManager.startDownload(
                    selectedLocation, initialPayload ?: selectedPayload,
                    { meshes: Array<MeshDownloaderData> ->
                        setDownloadedMeshChunks(meshes)
                    })
            }

            MeshDownloadStatus.COMPLETED -> {

            }

            else -> {}
        }
        while (true) {
            if (meshRenderer.needsFullMeshReload) {
                targetAnchorNode?.let { node -> meshRenderer.createMeshNodes(node) }
            }
            delay(200)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { vpsManager.createAnchor() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Location selector or coverage notice - only shown when not tracking
            if (!isTracking) {
                Spacer(modifier = Modifier.height(4.dp))

                if (initialPayload.isNullOrBlank()) {
                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = !dropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedLocation,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Location") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(0.8f)
                                .background(Color.White.copy(alpha = 0.7f)),
                        )

                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            payloadOptions.keys.forEach { locationName ->
                                DropdownMenuItem(text = { Text(locationName) }, onClick = {
                                    selectedLocation = locationName
                                    dropdownExpanded = false
                                })
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Anchor payload found via VPS Coverage",
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.DarkGray.copy(alpha = 0.5f))
                            .padding(8.dp),
                        color = Color(0xFF_FFFFFF)
                    )
                }
            }
        }

        // Info box for tracked anchors
        if (
            isTracking &&
            (localAnchorPayloads.isNotEmpty() || targetAnchorId != null)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 70.dp)
                    .fillMaxWidth(0.45f)
                    .heightIn(max = 300.dp)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            ) {
                val totalAnchors =
                    localAnchorPayloads.size + if (targetAnchorId != null) 1 else 0
                Text(
                    text = "Tracked Anchors ($totalAnchors/${vpsManager.maxTrackedAnchors}):",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                LazyColumn {
                    targetAnchorId?.let { targetId ->
                        item(key = "target-$targetId") {
                            val state = targetAnchorState
                            val stateColor = when (state) {
                                AnchorTrackingState.TRACKED -> Color.Green
                                AnchorTrackingState.NOT_TRACKED -> Color.Red
                                AnchorTrackingState.LIMITED -> Color.Yellow
                            }
                            Row(
                                modifier = Modifier.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(stateColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Target ${
                                        targetId.toString().substring(0, 8)
                                    }... - $state",
                                    color = Color.White,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                    items(localAnchorPayloads.keys.toList()) { anchorId ->
                        val state = localAnchorStates[anchorId] ?: AnchorTrackingState.NOT_TRACKED
                        val stateColor = when (state) {
                            AnchorTrackingState.TRACKED -> Color.Green
                            AnchorTrackingState.NOT_TRACKED -> Color.Red
                            AnchorTrackingState.LIMITED -> Color.Yellow
                        }
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(stateColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Local ${anchorId.toString().substring(0, 8)}... - $state",
                                color = Color.White,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }

        // Info box for geolocation
        if (isTracking) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 70.dp)
                    .fillMaxWidth(0.45f)
                    .heightIn(max = 300.dp)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            ) {
                Text(
                    text = "VPS Localized GPS:",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = geolocationInfo,
                    color = Color.White,
                    fontSize = 9.sp
                )
            }
        }

        // Start/Stop VPS button
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (!isTracking) {
                        warningMessage = null
                        val effectivePayload = initialPayload ?: selectedPayload
                        vpsManager.startTracking(effectivePayload)
                        // Force to start mesh download if mesh download is in ready state and starting VPS
                        if (meshDownloadStatus == MeshDownloadStatus.READY) {
                            meshDownloadManager.startDownload(
                                selectedLocation, initialPayload ?: selectedPayload,
                                { meshes: Array<MeshDownloaderData> ->
                                    setDownloadedMeshChunks(meshes)
                                })
                        }
                    } else {
                        vpsManager.stopTracking()
                        meshDownloadManager.cancelDownload(forceCancel = true)
                        warningMessage = null
                    }
                }
            ) {
                Text(if (isTracking) "Stop" else "Start VPS")
            }

            ErrorDisplay(errorMessage = warningMessage)
        }

        fun meshDownloadCallback(meshes: Array<MeshDownloaderData>) {
            setDownloadedMeshChunks(meshes)
        }

        // Mesh download status
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(end = 100.dp, bottom = 0.dp)
                .fillMaxWidth(0.45f)
                .heightIn(max = 300.dp)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(8.dp)
        ) {

            Text(
                text = "Mesh download status: $meshDownloadStatus",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}
