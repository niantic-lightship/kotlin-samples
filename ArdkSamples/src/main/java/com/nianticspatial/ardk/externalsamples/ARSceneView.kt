// Copyright 2025 Niantic.
package com.nianticspatial.ardk.externalsamples

import com.google.ar.core.Anchor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.compositionLocalOf
import com.google.ar.core.Config
import io.github.sceneview.ar.ARScene
import android.content.Context
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.loaders.MaterialLoader
import java.nio.ByteBuffer
import io.github.sceneview.node.*
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.ar.node.AnchorNode

/**
 * An external reference to the list of nodes so it can be dynamically modified.
 * This is a common pattern for managing lists that need external changes in Compose.
 */
val arChildNodes = mutableStateListOf<Node>()

/**
 * CompositionLocal to provide the AR scene's engine to composables inside ARSceneView.
 * This ensures all nodes use the same Filament engine.
 */
val LocalSceneEngine = compositionLocalOf<Engine> {
  error("No SceneEngine provided")
}

/**
 * CompositionLocal to provide the AR scene's material loader to composables inside ARSceneView.
 */
val LocalSceneMaterialLoader = compositionLocalOf<MaterialLoader> {
  error("No SceneMaterialLoader provided")
}

/**
 * Clean AR view using SceneView's ARScene.
 * This completely replaces the custom OpenGL rendering with SceneView's high-level AR API.
 *
 * ARScene automatically handles:
 * - AR session creation and management
 * - Camera background rendering (no manual OpenGL!)
 * - Frame updates
 * - Lifecycle management
 */
@Composable
fun ARSceneView(
    modifier: Modifier = Modifier,
    sessionManager: ARSessionManager,
    content: @Composable BoxScope.() -> Unit
) {

  // Create and own the Filament engine and material loader
  // ARSceneView is responsible for all rendering, so it should manage these resources
  val engine = rememberEngine()
  val materialLoader = rememberMaterialLoader(engine)

  Box(modifier = modifier.fillMaxSize()) {
    ARScene(
      planeRenderer = false,
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
          alpha = if (sessionManager.enabled.value) 1f else 0f
        },

      engine = engine,
      materialLoader = materialLoader,

      // Configure AR session
      sessionConfiguration = { session, config ->
        config.depthMode = when (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
          true -> Config.DepthMode.AUTOMATIC
          else -> Config.DepthMode.DISABLED
        }
        config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
      },

      // Give the SceneView session to our ARSessionManager so there's only ONE session
      onSessionCreated = { session ->
        // Provide SceneView's session to ARSessionManager
        // This prevents two sessions fighting over the camera
        sessionManager.setSession(session)
      },

      // Frame updates - pass to session manager for other components to use
      onSessionUpdated = { session, frame ->
        sessionManager.updateFrame(frame)
        // The camera background is already rendered automatically by SceneView!
      },

      childNodes = arChildNodes,
    )
    // Provide the scene engine and material loader to child composables
    // This allows any child composable to access the rendering resources
    androidx.compose.runtime.CompositionLocalProvider(
      LocalSceneEngine provides engine,
      LocalSceneMaterialLoader provides materialLoader
    ) {
      // Overlay UI content on top of AR view
      content()
    }
  }
}

fun addChildNode(node: Node) {
  arChildNodes.add(node)
}

fun clearChildNodes() {
  arChildNodes.clear()
}

/**
 * Creates a true unlit material instance with the specified color.
 * This uses a compiled Filament material with shadingModel: unlit.
 * NO PBR calculations, NO lighting, NO shadows - just flat color rendering.
 *
 * This is significantly more performant than PBR materials when rendering
 * thousands of objects like map points or debug visualizations.
 */
fun createUnlitColorMaterial(
  context: Context,
  materialLoader: MaterialLoader,
  color: androidx.compose.ui.graphics.Color
): MaterialInstance {
  // Load the pre-compiled unlit material
  val buffer = context.assets.open("materials/unlit_color.filamat").use {
    ByteBuffer.wrap(it.readBytes())
  }

  val material = materialLoader.createMaterial(buffer)
  val materialInstance = material.createInstance()

  // Set the base color parameter (RGBA as individual floats)
  materialInstance.setParameter(
    "baseColor",
    color.red, color.green, color.blue, color.alpha
  )

  return materialInstance
}

// Helper function to create an anchor node with a cube
// Engine and MaterialLoader should be obtained via CompositionLocal in composables
fun createAnchorNode(
  engine: Engine,
  materialLoader: MaterialLoader,
  anchor: Anchor
): AnchorNode {
  val anchorNode = AnchorNode(engine = engine, anchor = anchor)
  val cubeNode = CubeNode(
    engine,
    size = Float3(0.2f, 0.2f, 0.2f),
    center = Float3(0f, 0f, 0f),
    materialInstance = materialLoader.createColorInstance(Color.White.copy(alpha = 0.5f))
  )
  anchorNode.addChildNode(cubeNode)
  return anchorNode
}

fun addChildNodeToAnchor(childNode: Node) {

}

fun clearAnchorChildren() {

}


/**
 * Destroys this node and all of its children, removing it from the given parent collection.
 * Call this before destroying Filament material instances to avoid “still in use” aborts.
 */
fun Node.destroyRecursively(parentCollection: MutableCollection<Node>? = null) {
  // detach children first so none keeps a material instance alive
  childNodes.toList().forEach { child ->
    removeChildNode(child)
    child.destroyRecursively()      // recursive call handles nested nodes
  }

  parentCollection?.remove(this)    // e.g. arChildNodes
  destroy()
}
