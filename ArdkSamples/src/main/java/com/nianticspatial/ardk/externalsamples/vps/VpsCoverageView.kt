package com.nianticspatial.ardk.externalsamples.vps

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.nianticlabs.ardk.LatLng
import com.nianticspatial.ardk.externalsamples.ARDKSessionManager
import com.nianticspatial.ardk.externalsamples.HelpContent
import com.nianticspatial.ardk.externalsamples.Utils
import com.nianticspatial.ardk.externalsamples.common.ErrorDisplay
import com.nianticspatial.ardk.externalsamples.wps.GpsManager
import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Constants
private object VpsCoverageUiConstants {
  const val HINT_IMAGE_SIZE_DP = 60
  const val LOCATION_DECIMAL_PLACES = 6
}

@Serializable
object VpsCoverageRoute

/**
 * Main VPS View composable that handles VPS tracking functionality
 */
@Composable
fun VpsCoverageView(
  ardkManager: ARDKSessionManager,
  navHostController: NavHostController,
  helpContentState: MutableState<HelpContent?>
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val localContext = LocalContext.current

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

  val vpsCoverageManager = remember { VpsCoverageManager(ardkManager) }
  val gpsManager = remember { GpsManager(ardkManager) }

  // Let the manager handle cleaning itself up
  DisposableEffect (ardkManager, vpsCoverageManager) {
    lifecycleOwner.lifecycle.addObserver(vpsCoverageManager)
    lifecycleOwner.lifecycle.addObserver(gpsManager)
    onDispose {
      vpsCoverageManager.onDestroy(lifecycleOwner)
      gpsManager.onDestroy(lifecycleOwner)
      lifecycleOwner.lifecycle.removeObserver(vpsCoverageManager)
      lifecycleOwner.lifecycle.removeObserver(gpsManager)
    }
  }

  val latitude by gpsManager.gpsLatitude.collectAsState()
  val longitude by gpsManager.gpsLongitude.collectAsState()
  val currentLatLng by remember (latitude, longitude) {
    derivedStateOf {
      latitude?.let { lat ->
        longitude?.let { lng ->
          LatLng(lat, lng)
        }
      }
    }
  }

  val selectedAnchorPayload = remember { mutableStateOf<String?>(null) }
  val areaTargetsWithImages by vpsCoverageManager.areaTargetsWithImages.collectAsState()
  val isLoadingAreaTargets by vpsCoverageManager.isLoadingAreaTargets.collectAsState()
  val areaTargetsLoadingStatus by vpsCoverageManager.areaTargetsLoadingStatus.collectAsState()

  // Only used for receiving and displaying toast messages
  LaunchedEffect(vpsCoverageManager) {
    vpsCoverageManager.toasts.collect { message ->
      Toast.makeText(localContext, message, Toast.LENGTH_SHORT).show()
    }
  }

  if (selectedAnchorPayload.value != null) {
    // Switch to VPS tracking view using the selected payload
    navHostController.navigate(VPSRoute(selectedAnchorPayload.value))
  } else Box(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier
        .fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      LocationStatusDisplay(currentLocation = currentLatLng, context = localContext)
      LoadingIndicator(isLoading = isLoadingAreaTargets)

      if (areaTargetsWithImages.isEmpty() && !isLoadingAreaTargets) {
        Button(
          onClick = { vpsCoverageManager.findNearbyAreaTargets(ardkManager.arManager.lastLocation) },
          enabled = ardkManager.arManager.lastLocation != null
        ) {
          Text("Search for nearby area targets")
        }
      }

      when {
        areaTargetsWithImages.isNotEmpty() -> {
          AreaTargetSelection(
            areaTargetsWithImages = areaTargetsWithImages,
            currentLocation = currentLatLng,
            onTargetSelected = { selectedTarget -> selectedAnchorPayload.value = selectedTarget.areaTarget.localizationTarget.defaultAnchorPayload },
            onBackToSearch = vpsCoverageManager::resetSearch,
            modifier = Modifier.fillMaxSize()
          )
        }
        areaTargetsLoadingStatus == AreaTargetsLoadingStatus.Success && areaTargetsWithImages.isEmpty() && !isLoadingAreaTargets -> {
          Text(
            text = "No area targets found nearby.",
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp),
            textAlign = TextAlign.Center,
            color = Color.Gray
          )
        }
      }

      Spacer(modifier = Modifier.weight(1f))

      if (areaTargetsLoadingStatus is AreaTargetsLoadingStatus.Failed) {
        ErrorDisplay(errorMessage = "Failed to load area targets: ${(areaTargetsLoadingStatus as AreaTargetsLoadingStatus.Failed).errorCode}")
      } else if (areaTargetsLoadingStatus is AreaTargetsLoadingStatus.Timeout){
        ErrorDisplay(errorMessage = "Failed to load area targets: Timeout")
      }
    }
  }
}

/**
 * Display for location status with different states
 */
@Composable
private fun LocationStatusDisplay(currentLocation: LatLng?, context: Context) {
  when {
    currentLocation != null -> {
      Text(
        text = "Location: ${formatLocation(currentLocation)}",
        modifier = Modifier
          .fillMaxWidth()
          .background(Color.DarkGray.copy(alpha = 0.5f))
          .padding(8.dp),
        color = Color(0xFF_ffffff),
        textAlign = TextAlign.Center,
        fontSize = 12.sp
      )
    }

    Utils.hasLocationPermissions(context) -> {
      Text(
        text = "Getting your location...",
        modifier = Modifier
          .fillMaxWidth()
          .background(Color.DarkGray.copy(alpha = 0.5f))
          .padding(8.dp),
        color = Color(0xFF_FF8C00),
        textAlign = TextAlign.Center,
        fontSize = 12.sp
      )
    }

    else -> {
      Text(
        text = "Location permission required",
        modifier = Modifier
          .fillMaxWidth()
          .background(Color.DarkGray.copy(alpha = 0.5f))
          .padding(8.dp),
        color = Color.Red,
        textAlign = TextAlign.Center,
        fontSize = 12.sp
      )
    }
  }
}

/**
 * Loading indicator with text
 */
@Composable
private fun LoadingIndicator(isLoading: Boolean) {
  if (isLoading) {
    Text(
      text = "Searching for area targets...",
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.DarkGray.copy(alpha = 0.5f))
        .padding(8.dp),
      color = Color(0xFF_ffffff),
      textAlign = TextAlign.Center
    )
    CircularProgressIndicator()
  }
}

/**
 * Area target selection UI
 */
@Composable
private fun AreaTargetSelection(
  areaTargetsWithImages: List<AreaTargetWithImage>,
  currentLocation: LatLng?,
  onTargetSelected: (AreaTargetWithImage) -> Unit,
  onBackToSearch: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier.padding(16.dp)
  ) {
    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .padding(horizontal = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      items(areaTargetsWithImages) { areaTargetWithImage ->
        AreaTargetCard(
          areaTargetWithImage = areaTargetWithImage,
          currentLocation = currentLocation,
          onClick = { onTargetSelected(areaTargetWithImage) }
        )
      }
    }

    Button(
      onClick = onBackToSearch,
      modifier = Modifier
        .padding(top = 16.dp)
        .fillMaxWidth()
    ) {
      Text("Back to Search")
    }
  }
}

@Composable
private fun AreaTargetCard(
  areaTargetWithImage: AreaTargetWithImage,
  currentLocation: LatLng?,
  onClick: () -> Unit
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    onClick = onClick
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      HintImageDisplay(areaTargetWithImage = areaTargetWithImage)
      Column(
        modifier = Modifier
          .padding(start = 16.dp)
          .weight(1f)
      ) {
        Text(
          text = areaTargetWithImage.areaTarget.localizationTarget.name,
          fontWeight = FontWeight.Bold,
          fontSize = 16.sp
        )
        Text(
          text = "Localizability: ${areaTargetWithImage.areaTarget.coverageArea.localizability.name}",
          fontWeight = FontWeight.Bold,
          color = Color.DarkGray
        )
        currentLocation?.let {
          Text(
            text = "Distance: ${
              "%.1f".format(
                haversineDistanceMeters(
                  it,
                  areaTargetWithImage.areaTarget.localizationTarget.center
                )
              )
            } m",
            color = Color.Gray
          )
        }
        Text(
          text = "Tap to track this location",
          color = Color.LightGray
        )
      }
    }
  }
}

@Composable
private fun HintImageDisplay(areaTargetWithImage: AreaTargetWithImage) {
  Box(
    modifier = Modifier
      .size(VpsCoverageUiConstants.HINT_IMAGE_SIZE_DP.dp)
      .background(Color.LightGray),
    contentAlignment = Alignment.Center
  ) {
    when {
      areaTargetWithImage.imageLoading -> {
        CircularProgressIndicator(
          modifier = Modifier.size(24.dp),
          strokeWidth = 2.dp
        )
      }
      areaTargetWithImage.imageBitmap != null -> {
        Image(
          bitmap = areaTargetWithImage.imageBitmap,
          contentDescription = "Hint image for ${areaTargetWithImage.areaTarget.localizationTarget.name}",
          modifier = Modifier.size(VpsCoverageUiConstants.HINT_IMAGE_SIZE_DP.dp)
        )
      }
      else -> {
        Text(
          text = "📍",
          fontSize = 24.sp
        )
      }
    }
  }
}

// Helper functions

/**
 * Format location for display
 */
private fun formatLocation(location: LatLng): String {
  return String.format("%.${VpsCoverageUiConstants.LOCATION_DECIMAL_PLACES}f", location.lat) + ", " +
    String.format("%.${VpsCoverageUiConstants.LOCATION_DECIMAL_PLACES}f", location.lng)
}

private fun haversineDistanceMeters(start: LatLng, end: LatLng): Double {
  val earthRadius = 6_378_137.0
  val lat1 = Math.toRadians(start.lat)
  val lat2 = Math.toRadians(end.lat)
  val deltaLat = lat2 - lat1
  val deltaLon = Math.toRadians(end.lng - start.lng)
  val a = sin(deltaLat / 2).pow(2) +
    cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
  val c = 2 * atan2(sqrt(a), sqrt(1 - a))
  return earthRadius * c
}
