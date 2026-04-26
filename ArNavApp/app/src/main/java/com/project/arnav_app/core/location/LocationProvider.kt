package com.project.arnav_app.core.location

import android.location.Location
import kotlinx.coroutines.flow.Flow

interface LocationProvider {
    val locationFlow: Flow<Location>
}
