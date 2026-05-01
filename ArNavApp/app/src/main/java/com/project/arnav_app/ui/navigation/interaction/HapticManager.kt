package com.project.arnav_app.ui.navigation.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HapticManager(
    private val hapticFeedback: HapticFeedback,
    private val scope: CoroutineScope
) {
    fun onListeningStart() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun onAcknowledge() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun onConfirm() {
        scope.launch {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(100)
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun onError() {
        // LongPress is usually the strongest available through this API
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

@Composable
fun rememberHapticManager(scope: CoroutineScope): HapticManager {
    val hapticFeedback = LocalHapticFeedback.current
    return remember(hapticFeedback, scope) {
        HapticManager(hapticFeedback, scope)
    }
}
