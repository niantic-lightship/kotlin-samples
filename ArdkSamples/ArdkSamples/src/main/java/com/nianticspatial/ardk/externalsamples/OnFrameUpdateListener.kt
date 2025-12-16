// Copyright 2025 Niantic.

package com.nianticspatial.ardk.externalsamples

import com.google.ar.core.Frame

interface OnFrameUpdateListener {
    fun onFrameUpdate(frame: Frame)
}
