// Copyright 2025 Niantic.
package com.nianticspatial.ardk.externalsamples

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nianticlabs.ardk.ARDKSession
import com.nianticspatial.ardk.externalsamples.vps.VpsCoverageRoute
import com.nianticspatial.ardk.externalsamples.wps.WpsRoute
import kotlinx.serialization.Serializable

@Serializable
object SelectorRoute

@Composable
fun SelectorView(navHostController: NavHostController, ardkSession: ARDKSession) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        Image(
          painter = painterResource(id = R.drawable.icon),
          contentDescription = "App Logo",
        )
        Button(onClick = { navHostController.navigate(VpsCoverageRoute) }) { Text(text = "VPS View") }
        Button(onClick = { navHostController.navigate(WpsRoute) }) { Text(text = "WPS View") }
        Text(text = "ARDK v" + ardkSession.getVersion())
      }
    }
}
