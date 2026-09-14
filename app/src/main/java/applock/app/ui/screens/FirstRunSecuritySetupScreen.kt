package applock.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

import applock.app.AppLockApplication

import kotlinx.coroutines.delay

/**
 * First-run orchestration wrapper for the existing security editor.
 *
 * The existing SecuritySetupScreen owns the actual PIN / Pattern /
 * Biometric UI. This wrapper detects when the first credential has
 * been successfully stored and advances AppLockRoot to the normal
 * application.
 */
@Composable
fun FirstRunSecuritySetupScreen(
    onComplete: () -> Unit
) {

    val context =
        LocalContext.current

    val app =
        context.applicationContext as AppLockApplication

    val repository =
        app.repository

    var completed by remember {
        mutableStateOf(
            repository.authenticationConfigured()
        )
    }

    LaunchedEffect(Unit) {

        while (!completed) {

            if (
                repository.authenticationConfigured()
            ) {
                completed = true
                onComplete()
                break
            }

            delay(250)
        }
    }

    if (!completed) {
        Box(
            modifier =
                Modifier.fillMaxSize()
        ) {
            SecuritySetupScreen()
        }
    }
}