package com.nianticspatial.ardk.externalsamples

import android.util.Size
import com.nianticlabs.ardk.Orientation


/**
 * Interface for renderers that can draw within the AR scene.
 */
interface Renderer {
  /**
   * Compiles shader
   *
   * Should be called after opengl context is created, ie in onSurfaceCreated()
   * in a GLSurfaceView.Renderer
   */
  fun compile()

  /**
   * Draws whatever the renderer is capable of drawing.
   *
   * Must pass in the view and projection matrices. Any extra parameters
   * should be passed to the renderer through other renderer specific methods, and
   * stored internally.
   */
  fun draw(viewMatrix: FloatArray, projMatrix: FloatArray)
}

/**
 * Interface for renderers that can draw images within the AR scene.
 */
interface ImageRenderer: Renderer {
  /**
   * Inputs the UI orientation to use in the renderer.
   */
  fun setOrientation(orientation: Orientation)

  /**
   * Inputs the viewport size to the renderer.
   */
  fun setViewportSize(size: Size)
}
