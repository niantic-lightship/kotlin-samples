package com.nianticspatial.ardk.externalsamples.vps

import android.graphics.BitmapFactory
import android.location.Location
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.LifecycleOwner
import com.nianticlabs.ardk.AreaTarget
import com.nianticlabs.ardk.AsyncResult
import com.nianticlabs.ardk.LatLng
import com.nianticlabs.ardk.VpsCoverageError
import com.nianticspatial.ardk.externalsamples.ARDKSessionManager
import com.nianticspatial.ardk.externalsamples.FeatureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class AreaTargetWithImage(
  val areaTarget: AreaTarget,
  val imageBitmap: ImageBitmap? = null,
  val imageLoading: Boolean = false
)

sealed class AreaTargetsLoadingStatus {
  data object NotStarted: AreaTargetsLoadingStatus()
  data object Running: AreaTargetsLoadingStatus()
  data object Success: AreaTargetsLoadingStatus()
  data object Timeout: AreaTargetsLoadingStatus()
  data class Failed(val errorCode: VpsCoverageError): AreaTargetsLoadingStatus()
}

class VpsCoverageManager(
  private val ardkManager: ARDKSessionManager,
  private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : FeatureManager() {
  companion object {
    const val SEARCH_RADIUS_METERS = 500
    const val MAX_AREA_TARGETS = 20
    const val LOG_TAG = "VpsCoverageManager"
  }

  private val vpsCoverageSession = ardkManager.session.vpsCoverage.acquire()

  // MutableStateFlow is used so the data can be observed from both Compose
  // and non-UI consumers (ViewModels, tests, etc.) while still exposing a
  // reactive stream that supports multiple collectors.
  private val _areaTargetsWithImages = MutableStateFlow<List<AreaTargetWithImage>>(emptyList())
  var areaTargetsWithImages: StateFlow<List<AreaTargetWithImage>> = _areaTargetsWithImages

  private val _loadingAreaTargets = MutableStateFlow(false)
  var isLoadingAreaTargets: StateFlow<Boolean> = _loadingAreaTargets

  private val _areaTargetsLoadingStatus = MutableStateFlow<AreaTargetsLoadingStatus>(AreaTargetsLoadingStatus.NotStarted)
  var areaTargetsLoadingStatus: StateFlow<AreaTargetsLoadingStatus> = _areaTargetsLoadingStatus

  private var areaTargetPollingJob: Job? = null
  private val hintImageJobs = mutableMapOf<Int, Job>()

  @MainThread
  fun findNearbyAreaTargets(currentLocation: Location?) {
    if (currentLocation == null) {
      Log.e(LOG_TAG, "Current location unavailable.")
      return
    }
    if (_loadingAreaTargets.value) return

    _loadingAreaTargets.value = true
    _areaTargetsWithImages.value = emptyList()

    // Cancel existing jobs
    areaTargetPollingJob?.cancel()
    cancelHintImageJobs()

    val userLocation = LatLng(currentLocation.latitude, currentLocation.longitude)

    _areaTargetsLoadingStatus.value = AreaTargetsLoadingStatus.Running
    areaTargetPollingJob = coroutineScope.launch {
      getAreaTargets(userLocation)
    }
  }

  private fun cancelHintImageJobs() {
    hintImageJobs.values.forEach { it.cancel() }
    hintImageJobs.clear()
  }

  private suspend fun getAreaTargets(userLocation: LatLng) {
    val result = vpsCoverageSession.getAreaTargets(
      latLng = userLocation,
      radius = SEARCH_RADIUS_METERS
    )

    when (result) {
      is AsyncResult.Success -> {
        val sortedTargets = result.value
          .sortedBy { calculateDistance(userLocation, it.coverageArea.center) }
          .take(MAX_AREA_TARGETS)

        val targetsWithImages = sortedTargets.map { AreaTargetWithImage(areaTarget = it) }

        withContext(Dispatchers.Main) {
          _loadingAreaTargets.value = false
          _areaTargetsWithImages.value = targetsWithImages
          _areaTargetsLoadingStatus.value = AreaTargetsLoadingStatus.Success
        }

        sortedTargets.forEachIndexed { index, areaTarget ->
          val hintUrl = areaTarget.localizationTarget.hintImageUrl
          if (hintUrl.isNotBlank()) {
            loadHintImageForTarget(index, hintUrl)
          }
        }
      }

      is AsyncResult.Timeout -> {
        withContext(Dispatchers.Main) {
          _loadingAreaTargets.value = false
          _areaTargetsLoadingStatus.value = AreaTargetsLoadingStatus.Timeout
        }
      }

      is AsyncResult.Error -> {
        withContext(Dispatchers.Main) {
          _loadingAreaTargets.value = false
          _areaTargetsLoadingStatus.value = AreaTargetsLoadingStatus.Failed(result.code)
        }
      }
    }
  }

  private fun loadHintImageForTarget(targetIndex: Int, hintImageUrl: String) {
    hintImageJobs[targetIndex]?.cancel()
    val job = coroutineScope.launch {
      try {
        updateAreaTarget(targetIndex) { it.copy(imageLoading = true) }
        val result = vpsCoverageSession.getHintImage(hintImageUrl)
        when (result) {
          is AsyncResult.Success -> {
            val imageBitmap = try {
              withContext(Dispatchers.IO) {
                BitmapFactory.decodeByteArray(result.value, 0, result.value.size)
              }?.asImageBitmap()
            } catch (e: Exception) {
              Log.e(LOG_TAG, "Failed to decode image: ${e.message}")
              null
            }
            updateAreaTarget(targetIndex) {
              it.copy(
                imageBitmap = imageBitmap,
                imageLoading = false
              )
            }
          }

          is AsyncResult.Timeout -> {
            Log.e(LOG_TAG, "Failed to load hint image, request timed out")
            updateAreaTarget(targetIndex) { it.copy(imageLoading = false) }
          }

          is AsyncResult.Error -> {
            Log.e(LOG_TAG, "Failed to load hint image: ${result.code}")
            updateAreaTarget(targetIndex) { it.copy(imageLoading = false) }
          }
        }
      }
      finally {
        hintImageJobs.remove(targetIndex)
      }
    }
    hintImageJobs[targetIndex] = job
  }

  private suspend fun updateAreaTarget(
    areaTargetIndex: Int,
    transform: (AreaTargetWithImage) -> AreaTargetWithImage
  ) {
    withContext(Dispatchers.Main) {
      val current = _areaTargetsWithImages.value.toMutableList()
      if (areaTargetIndex in current.indices) {
        current[areaTargetIndex] = transform(current[areaTargetIndex])
        _areaTargetsWithImages.value = current.toList()
      }
    }
  }

  @MainThread
  fun resetSearch() {
    cancelHintImageJobs()
    _areaTargetsWithImages.value = emptyList()
    _loadingAreaTargets.value = false
    _areaTargetsLoadingStatus.value = AreaTargetsLoadingStatus.NotStarted
  }

  private fun calculateDistance(location1: LatLng, location2: LatLng): Double {
    val earthRadius = 6_371_009.0
    val dLat = Math.toRadians(location2.lat - location1.lat)
    val dLng = Math.toRadians(location2.lng - location1.lng)
    val a = sin(dLat / 2).pow(2) +
      cos(Math.toRadians(location1.lat)) *
      cos(Math.toRadians(location2.lat)) *
      sin(dLng / 2).pow(2)
    return 2 * earthRadius * asin(sqrt(a))
  }

  override fun onDestroy(owner: LifecycleOwner) {
    super.onDestroy(owner)
    areaTargetPollingJob?.cancel()
    cancelHintImageJobs()
    vpsCoverageSession.close()
  }
}
