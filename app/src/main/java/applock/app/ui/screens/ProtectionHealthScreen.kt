package applock.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication
import applock.app.domain.AuthMethod
import applock.app.domain.HealthState
import applock.app.domain.ProtectionReason
import applock.app.ui.components.PremiumCard
import applock.app.ui.components.StatusPill
import applock.app.security.AntiTamperManager

@Composable
fun ProtectionHealthScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication

    val snapshot by app.repository.protectionSnapshot.collectAsState()

    val method = app.repository.getAuthMethod()

    val biometricReady =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS

    val authReady = when (method) {
        AuthMethod.PIN ->
            app.repository.hasPin()

        AuthMethod.PATTERN ->
            app.repository.hasPattern()

        AuthMethod.BIOMETRIC ->
            biometricReady && app.repository.hasPin()
    }

    val power = context.getSystemService(PowerManager::class.java)

    val batteryOk =
        power?.isIgnoringBatteryOptimizations(
            context.packageName
        ) == true

    /*
     * Device Admin is intentionally treated as a separate security
     * layer from the normal Lock Engine checks.
     *
     * Missing Device Admin does NOT disable AppLock.
     * It means AppLock is operating in limited protection mode.
     */
    val deviceAdminEnabled =
        AntiTamperManager.isDeviceAdminActive(context)

    val strongOwner =
        AntiTamperManager.isStrongOwner(context)

    val checks = listOf(
        HealthRow(
            "Secure storage",
            if (snapshot.secureStorageHealthy) {
                HealthState.GREEN
            } else {
                HealthState.RED
            },
            if (snapshot.secureStorageHealthy) {
                "Keystore-backed security data is readable."
            } else {
                "Encrypted security data cannot be trusted."
            }
        ),

        HealthRow(
            "Lock Engine",
            if (
                snapshot.accessibilityEnabled &&
                snapshot.credentialConfigured
            ) {
                HealthState.GREEN
            } else {
                HealthState.RED
            },
            if (
                snapshot.accessibilityEnabled &&
                snapshot.credentialConfigured
            ) {
                "Detection and authentication are available."
            } else {
                "A required protection component is unavailable."
            }
        ),

        HealthRow(
            "Authentication",
            if (authReady) {
                HealthState.GREEN
            } else {
                HealthState.RED
            },
            if (authReady) {
                "${method.name.lowercase().replaceFirstChar { it.uppercase() }} is configured."
            } else {
                "Configure a working authentication method."
            }
        ),

        HealthRow(
            "Protected Apps",
            if (snapshot.protectedAppCount > 0) {
                HealthState.GREEN
            } else {
                HealthState.YELLOW
            },
            if (snapshot.protectedAppCount > 0) {
                "${snapshot.protectedAppCount} app(s) selected for protection."
            } else {
                "No apps are selected for protection yet."
            }
        ),

        HealthRow(
            "Accessibility",
            if (snapshot.accessibilityEnabled) {
                HealthState.GREEN
            } else {
                HealthState.RED
            },
            if (snapshot.accessibilityEnabled) {
                "Android reports the AppLock service as enabled."
            } else {
                "Enable AppLock in Android Accessibility settings."
            }
        ),

        HealthRow(
            "Device Protection",
            when {
                strongOwner ->
                    HealthState.GREEN

                deviceAdminEnabled ->
                    HealthState.GREEN

                else ->
                    HealthState.YELLOW
            },
            when {
                strongOwner ->
                    "Strong Device/Profile Owner protection is active."

                deviceAdminEnabled ->
                    "Device Administrator is enabled. AppLock has additional anti-tamper protection."

                else ->
                    "Device protection is not enabled. AppLock is operating in limited protection mode."
            }
        ),

        HealthRow(
            "Battery Restrictions",
            if (batteryOk) {
                HealthState.GREEN
            } else {
                HealthState.YELLOW
            },
            if (batteryOk) {
                "No app-specific battery optimization detected."
            } else {
                "Some OEMs may restrict background detection."
            }
        ),

        HealthRow(
            "Boot Recovery",
            if (
                snapshot.accessibilityEnabled &&
                snapshot.credentialConfigured
            ) {
                HealthState.GREEN
            } else {
                HealthState.RED
            },
            "Previous unlock sessions are discarded after restart; protection is not claimed before the service is available."
        )
    )

    /*
     * Device Admin missing is intentionally YELLOW rather than RED.
     *
     * AppLock remains usable in limited mode.
     */
    val overallState = when {
        checks.any { it.state == HealthState.RED } ->
            HealthState.RED

        checks.any { it.state == HealthState.YELLOW } ->
            HealthState.YELLOW

        else ->
            HealthState.GREEN
    }

    val overall = when (overallState) {
        HealthState.GREEN ->
            "Protected"

        HealthState.YELLOW ->
            "Limited protection"

        HealthState.RED ->
            "Not protected"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        /*
         * Persistent strong warning while Device Admin is missing.
         */
        if (!deviceAdminEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "Protection is not fully enabled",
                        style = MaterialTheme.typography.titleLarge,
                        color =
                            MaterialTheme.colorScheme.onErrorContainer
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    Text(
                        text =
                            "AppLock is running in limited protection mode. " +
                                "Your selected apps can still be protected, " +
                                "but Device-level uninstall and tamper " +
                                "protection is reduced.",
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            MaterialTheme.colorScheme.onErrorContainer
                    )

                    Spacer(
                        modifier = Modifier.height(16.dp)
                    )

                    Button(
                        onClick = {
                            AntiTamperManager.requestDeviceAdmin(
                                context as Activity
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enable Device Protection")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.HealthAndSafety,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                Text(
                    text = overall,
                    style = MaterialTheme.typography.headlineMedium
                )

                Text(
                    text = when (snapshot.reason) {
                        ProtectionReason.PROTECTED ->
                            "All required protection components are available."

                        ProtectionReason.NO_PROTECTED_APPS ->
                            "Authentication and detection are ready, but no protected apps are selected."

                        ProtectionReason.ACCESSIBILITY_DISABLED ->
                            "Protection is disabled because Android Accessibility is not enabled."

                        ProtectionReason.SECURITY_SETUP_REQUIRED ->
                            "Security setup is incomplete."

                        ProtectionReason.SECURE_STORAGE_ERROR ->
                            "Encrypted AppLock security data cannot be trusted."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (snapshot.forceStopRecoveryPending) {
                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    Text(
                        text =
                            "A previous force-stop was detected. " +
                                "Unlock sessions were cleared.",
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(
                    modifier = Modifier.height(10.dp)
                )

                StatusPill(
                    text = if (overallState == HealthState.GREEN) "Healthy" else "Action required",
                    positive = overallState == HealthState.GREEN
                )
            }
        }

        checks.forEach { check ->

            PremiumCard(
                title = check.title,
                subtitle = check.detail
            ) {

                if (
                    (
                        check.title == "Lock Engine" ||
                            check.title == "Accessibility"
                    ) &&
                    !snapshot.accessibilityEnabled
                ) {
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_ACCESSIBILITY_SETTINGS
                                )
                            )
                        }
                    ) {
                        Text("Enable Accessibility")
                    }
                }

                if (
                    check.title == "Device Protection" &&
                    !deviceAdminEnabled
                ) {
                    Button(
                        onClick = {
                            AntiTamperManager.requestDeviceAdmin(
                                context as Activity
                            )
                        }
                    ) {
                        Text("Enable Device Protection")
                    }
                }
            }
        }
    }
}

private data class HealthRow(
    val title: String,
    val state: HealthState,
    val detail: String
)