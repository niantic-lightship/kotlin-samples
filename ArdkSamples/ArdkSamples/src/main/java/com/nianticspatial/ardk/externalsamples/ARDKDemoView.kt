// Copyright 2025 Niantic.
package com.nianticspatial.ardk.externalsamples

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.nianticlabs.ardk.ARDKSession
import com.nianticspatial.ardk.externalsamples.vps.VPSRoute
import com.nianticspatial.ardk.externalsamples.vps.VPSView
import com.nianticspatial.ardk.externalsamples.vps.VpsCoverageRoute
import com.nianticspatial.ardk.externalsamples.vps.VpsCoverageView
import com.nianticspatial.ardk.externalsamples.wps.WpsRoute
import com.nianticspatial.ardk.externalsamples.wps.WpsView

@Composable
fun ARDKDemoView(modifier: Modifier = Modifier, activity: Activity) {
    val navController = rememberNavController()
    val sessionManager = remember { ARSessionManager(activity) }
    val ardkSession = ARDKSession(apiKey = BuildConfig.API_KEY)
    val ardkSessionManager = ARDKSessionManager(activity, ardkSession, sessionManager)
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.addObserver(sessionManager)
        lifecycleOwner.lifecycle.addObserver(ardkSessionManager)
    }

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        sessionManager = sessionManager
    ) {
        NavHost(navController = navController, startDestination = SelectorRoute, modifier) {
            composable<SelectorRoute> { backStackEntry ->
                sessionManager.setEnabled(false)
                SelectorView(navController, ardkSession)
            }

            composable<VPSRoute> { backStackEntry ->
                sessionManager.setEnabled(true)
                val args = backStackEntry.toRoute<VPSRoute>()
                BackHelpScaffold(navController) { helpContentState ->
                    VPSView(activity, ardkSessionManager, helpContentState, args.payload)
                }
            }

            composable<WpsRoute> { backStackEntry ->
                sessionManager.setEnabled(true)
                BackHelpScaffold(navController) { helpContentState ->
                    WpsView(activity, ardkSessionManager, helpContentState, modifier)
                }
            }

            composable<VpsCoverageRoute> { backStackEntry ->
                sessionManager.setEnabled(true)
                BackHelpScaffold(navController) { helpContentState ->
                    VpsCoverageView(ardkSessionManager, navController, helpContentState)
                }
            }
        }
    }
}

@Composable
fun BackButtonScaffold(
    navController: NavController,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()

        IconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, bottom = 16.dp)
                .size(56.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
    }
}
