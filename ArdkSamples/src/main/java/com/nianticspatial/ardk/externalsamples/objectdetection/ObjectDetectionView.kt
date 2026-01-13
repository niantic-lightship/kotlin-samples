package com.nianticspatial.ardk.externalsamples.objectdetection

import android.util.Log
import android.util.Size as AndroidSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.Paint as AndroidPaint
import android.graphics.Rect as AndroidRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nianticlabs.ardk.ARDKResult
import com.nianticlabs.ardk.objectdetection.height
import com.nianticlabs.ardk.objectdetection.width
import com.nianticspatial.ardk.externalsamples.ARDKSessionManager
import com.nianticspatial.ardk.externalsamples.HelpContent
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.Serializable

@Serializable
object ObjectDetectionRoute

@OptIn(DelicateCoroutinesApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ObjectDetectionView(
    ardkManager: ARDKSessionManager,
    helpContentState: MutableState<HelpContent?>
) {

    val objectDetectionManager =
        remember { ObjectDetectionManager(ardkManager) }

    // Aggregation manager is used to aggregate objects across frames to prevent jitter
    val aggregationManager =
        remember { AggregationManager(objectDetectionManager) }

    var aggregatedObjects by remember { mutableStateOf<List<AggregatedObject>>(emptyList()) }

    val lifecycleOwner = LocalLifecycleOwner.current

    var viewportSize: AndroidSize by remember { mutableStateOf(AndroidSize(1, 1)) }

    // Set Help contents
    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text = "Object Detection Sample Help\n\nThis sample identifies, identifies and mark objects" +
                    " as detected, then draws a bounding box around the object.\nTO USE:\nPress the \"Start\" button," +
                    " and move the camera around, pointing at the object or objects you want to identify. Red boxes will " +
                    " appear around the objects detected along with their labels and confidence level.",
                color = Color.White
            )
        }
        onDispose { helpContentState.value = null }
    }

    // Lifecycle management for ObjectDetectionManager
    DisposableEffect(lifecycleOwner, objectDetectionManager) {
        lifecycleOwner.lifecycle.addObserver(objectDetectionManager)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(objectDetectionManager)
        }
    }

    // Cleanup aggregation manager when view is disposed
    DisposableEffect(aggregationManager) {
        onDispose {
            aggregationManager.clear()
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .onGloballyPositioned {
            // Capture the viewport size
                layoutCoordinates ->
            viewportSize = AndroidSize(layoutCoordinates.size.width, layoutCoordinates.size.height)
        }) {

        // Toggle button
        Button(
            onClick = {
                if (!objectDetectionManager.detectionStarted) {

                    objectDetectionManager.startDetection(viewportSize) { processedDetectionsResult ->
                        when (processedDetectionsResult) {
                            is ARDKResult.Success -> {
                                aggregatedObjects = aggregationManager.update(objectDetectionManager.viewTransformedDetectedObjects)
                                Log.d("ARDK", "Detected ${aggregatedObjects.size} aggregated objects")
                            }
                            is ARDKResult.Error -> {
                                Log.d("ARDK", "Detection error: ${processedDetectionsResult.code}")
                            }
                        }
                    }
                } else {
                    objectDetectionManager.stopDetection()
                    // Clear aggregated objects when stopping detection
                    aggregationManager.clear()
                    aggregatedObjects = emptyList()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(if (objectDetectionManager.detectionStarted) "Stop Detection" else "Start Detection")
        }

        val density = LocalDensity.current
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = with(density) { 2.dp.toPx() }
            val textSize = with(density) { 12.sp.toPx() }
            val labelPadding = with(density) { 4.dp.toPx() }

            aggregatedObjects.forEach { obj ->
                val rect = obj.rect

                // Draw bounding box border
                drawRect(
                    color = Color.Red,
                    topLeft = Offset(rect.left, rect.top),
                    size = ComposeSize(rect.width(), rect.height()),
                    style = Stroke(width = strokeWidth)
                )

                // Draw label background and text
                val className = obj.className.ifBlank { "Unknown" }
                val labelText = "${className} (${String.format("%.2f", obj.confidence)})"

                // Measure text to determine label background size
                val textPaint = AndroidPaint().apply {
                    color = android.graphics.Color.WHITE
                    this.textSize = textSize
                    isAntiAlias = true
                }
                val textBounds = AndroidRect()
                textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)

                val labelWidth = textBounds.width() + labelPadding * 2
                val labelHeight = textBounds.height() + labelPadding * 2
                val labelTop = rect.top.coerceAtLeast(0f)
                val labelLeft = rect.left.coerceAtLeast(0f)

                // Draw label background
                drawRect(
                    color = Color.DarkGray.copy(alpha = 0.7f),
                    topLeft = Offset(labelLeft, labelTop),
                    size = ComposeSize(labelWidth, labelHeight)
                )

                // Draw text
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        labelText,
                        labelLeft + labelPadding,
                        labelTop + labelHeight - labelPadding,
                        textPaint
                    )
                }
            }
        }
    }
}
