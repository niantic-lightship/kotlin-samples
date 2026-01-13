// Copyright 2025 Niantic.
package com.nianticspatial.ardk.externalsamples

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nianticlabs.ardk.ARDKSession
import com.nianticspatial.ardk.externalsamples.objectdetection.ObjectDetectionRoute
import com.nianticspatial.ardk.externalsamples.depth.DepthRoute
import com.nianticspatial.ardk.externalsamples.vps.VpsCoverageRoute
import com.nianticspatial.ardk.externalsamples.wps.WpsRoute
import com.nianticspatial.ardk.externalsamples.meshing.MeshingRoute
import com.nianticspatial.ardk.externalsamples.semantics.SemanticsRoute
import kotlinx.serialization.Serializable

@Serializable
object SelectorRoute

@Composable
fun SelectorView(navHostController: NavHostController, ardkSession: ARDKSession) {

    val buttonModifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 16.dp)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.icon),
                contentDescription = "App Logo",
            )
            Button(
                onClick = { navHostController.navigate(VpsCoverageRoute) },
                modifier = buttonModifier
            ) { Text(text = "VPS") }
            Button(
                onClick = { navHostController.navigate(WpsRoute) },
                modifier = buttonModifier
            ) { Text(text = "WPS") }
            Button(
                onClick = { navHostController.navigate(ObjectDetectionRoute) },
                modifier = buttonModifier
            ) { Text(text = "Object Detection") }
            Button(
                onClick = { navHostController.navigate(DepthRoute) },
                modifier = buttonModifier
            ) { Text(text = "Depth") }
            Button(
                onClick = { navHostController.navigate(MeshingRoute) },
                modifier = buttonModifier
            ) { Text(text = "Live Meshing") }
            Button(
                onClick = { navHostController.navigate(SemanticsRoute) },
                modifier = buttonModifier
            ) { Text(text = "Semantics") }
            Text(text = "ARDK v" + ardkSession.getVersion())
        }
    }
}
