package applock.app.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import android.graphics.drawable.Drawable

import applock.app.AppLockApplication
import applock.app.ads.AdsConsentManager
import applock.app.ads.BannerAd
import applock.app.security.AppLockDeviceAdminReceiver
import applock.app.ui.components.AppDrawer
import applock.app.ui.screens.AboutScreen
import applock.app.ui.screens.AccessibilityDisclosureScreen
import applock.app.ui.screens.CustomizationScreen
import applock.app.ui.screens.DeviceAdminExplanationScreen
import applock.app.ui.screens.FirstRunSecuritySetupScreen
import applock.app.ui.screens.HomeScreen
import applock.app.ui.screens.OnboardingScreen
import applock.app.ui.screens.ProtectionHealthScreen
import applock.app.ui.screens.ProtectedAppsScreen
import applock.app.ui.screens.SecuritySetupScreen
import applock.app.ui.screens.SettingsScreen
import applock.app.ui.screens.SmartLockScreen
import applock.app.ui.theme.AppLockTheme

import kotlinx.coroutines.launch

private const val FIRST_RUN_ONBOARDING = "onboarding"
private const val FIRST_RUN_DEVICE_ADMIN = "device_admin"
private const val FIRST_RUN_DISCLOSURE = "disclosure"
private const val FIRST_RUN_SECURITY = "security_setup"
private const val FIRST_RUN_DONE = "done"

@Composable
private fun AppLockIconPainter(): BitmapPainter {
    val context = LocalContext.current

    return remember(context) {
        val drawable: Drawable =
            context.packageManager.getApplicationIcon(
                context.applicationInfo
            )

        BitmapPainter(
            drawable
                .toBitmap(
                    width = 192,
                    height = 192
                )
                .asImageBitmap()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockRoot(
    openProtectedApps: Boolean = false
) {

    val context = LocalContext.current

    val app =
        context.applicationContext as AppLockApplication

    val repository = app.repository

    val theme by repository.theme.collectAsState()

    val drawerState =
        rememberDrawerState(DrawerValue.Closed)

    val scope =
        rememberCoroutineScope()

    var destination by remember(openProtectedApps) {
        mutableStateOf(
            if (openProtectedApps) "apps" else "home"
        )
    }

    /*
     * Device Admin is a real Android system state.
     *
     * Do not use the result code alone because Android OEMs can
     * return different values. Always verify isAdminActive().
     */
    val devicePolicyManager =
        remember(context) {
            context.getSystemService(
                DevicePolicyManager::class.java
            )
        }

    val deviceAdminComponent =
        remember(context) {
            ComponentName(
                context,
                AppLockDeviceAdminReceiver::class.java
            )
        }

    var deviceAdminActive by remember {
        mutableStateOf(
            devicePolicyManager.isAdminActive(
                deviceAdminComponent
            )
        )
    }

    val lifecycleOwner =
        LocalLifecycleOwner.current

    /*
     * Re-check Device Admin whenever AppLock returns from the
     * Android system confirmation screen or Settings.
     */
    DisposableEffect(
        lifecycleOwner,
        devicePolicyManager,
        deviceAdminComponent
    ) {
        val observer =
            LifecycleEventObserver { _, event ->

                if (
                    event == Lifecycle.Event.ON_RESUME
                ) {
                    deviceAdminActive =
                        devicePolicyManager.isAdminActive(
                            deviceAdminComponent
                        )
                }
            }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(
                observer
            )
        }
    }

    /*
     * Determine the first-run state from persisted state and
     * the actual Device Admin state.
     */
    var firstRunStep by remember {
        mutableStateOf(
            when {

                !repository.isOnboardingComplete() ->
                    FIRST_RUN_ONBOARDING

                !FirstRunSetupState.isDeviceAdminDecisionComplete(
                    context
                ) ->
                    FIRST_RUN_DEVICE_ADMIN

                !repository.disclosureAccepted() ->
                    FIRST_RUN_DISCLOSURE

                !repository.authenticationConfigured() ->
                    FIRST_RUN_SECURITY

                else ->
                    FIRST_RUN_DONE
            }
        )
    }

    /*
     * Keep the first-run state synchronized with actual Device
     * Admin state after returning from Android's confirmation UI.
     */
    LaunchedEffect(
        deviceAdminActive,
        firstRunStep
    ) {
        if (
            firstRunStep ==
            FIRST_RUN_DEVICE_ADMIN &&
            deviceAdminActive
        ) {
            FirstRunSetupState.markDeviceAdminDecisionComplete(
                context
            )

            firstRunStep =
                if (
                    !repository.disclosureAccepted()
                ) {
                    FIRST_RUN_DISCLOSURE
                } else if (
                    !repository.authenticationConfigured()
                ) {
                    FIRST_RUN_SECURITY
                } else {
                    FIRST_RUN_DONE
                }
        }
    }

    /*
     * Launch Android's actual Device Admin activation flow.
     *
     * Android owns the confirmation screen. AppLock cannot silently
     * activate Device Admin.
     */
    val deviceAdminLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.StartActivityForResult()
        ) {
            deviceAdminActive =
                devicePolicyManager.isAdminActive(
                    deviceAdminComponent
                )

            if (deviceAdminActive) {
                FirstRunSetupState
                    .markDeviceAdminDecisionComplete(
                        context
                    )

                firstRunStep =
                    if (
                        !repository.disclosureAccepted()
                    ) {
                        FIRST_RUN_DISCLOSURE
                    } else if (
                        !repository.authenticationConfigured()
                    ) {
                        FIRST_RUN_SECURITY
                    } else {
                        FIRST_RUN_DONE
                    }
            }
        }

    /*
     * Ads are deliberately initialized only after first-run
     * security setup is complete.
     *
     * Authentication never depends on ads.
     */
    var adsReady by remember {
        mutableStateOf(
            app.isAdsInitialized()
        )
    }

    val consentManager =
        remember {
            AdsConsentManager(
                context.applicationContext
            )
        }

    LaunchedEffect(firstRunStep) {

        if (firstRunStep != FIRST_RUN_DONE) {
            return@LaunchedEffect
        }

        val activity =
            context.findActivity()
                ?: return@LaunchedEffect

        consentManager.requestIfRequired(
            activity
        ) { canRequestAds ->

            if (!canRequestAds) {
                adsReady = false
                return@requestIfRequired
            }

            app.initializeAdsIfAllowed {
                adsReady = true
            }
        }
    }

    AppLockTheme(theme) {

        when (firstRunStep) {

            /*
             * ---------------------------------------------------------
             * STEP 1 — ONBOARDING
             * ---------------------------------------------------------
             */
            FIRST_RUN_ONBOARDING -> {

                OnboardingScreen {

                    repository.setOnboardingComplete(
                        true
                    )

                    /*
                     * Device Admin is deliberately the next step.
                     */
                    firstRunStep =
                        FIRST_RUN_DEVICE_ADMIN
                }
            }

            /*
             * ---------------------------------------------------------
             * STEP 2 — DEVICE ADMIN
             * ---------------------------------------------------------
             */
            FIRST_RUN_DEVICE_ADMIN -> {

                DeviceAdminExplanationScreen(

                    onEnableProtection = {

                        val intent =
                            Intent(
                                DevicePolicyManager
                                    .ACTION_ADD_DEVICE_ADMIN
                            ).apply {

                                putExtra(
                                    DevicePolicyManager
                                        .EXTRA_DEVICE_ADMIN,
                                    deviceAdminComponent
                                )

                                putExtra(
                                    DevicePolicyManager
                                        .EXTRA_ADD_EXPLANATION,
                                    "Enable AppLock device protection " +
                                        "to strengthen tamper and " +
                                        "uninstall protection."
                                )
                            }

                        deviceAdminLauncher.launch(
                            intent
                        )
                    },

                    onNotNow = {

                        /*
                         * User explicitly chose to continue without
                         * Device Admin. Do not repeatedly block the
                         * first-run flow.
                         */
                        FirstRunSetupState
                            .markDeviceAdminDecisionComplete(
                                context
                            )

                        firstRunStep =
                            if (
                                !repository.disclosureAccepted()
                            ) {
                                FIRST_RUN_DISCLOSURE
                            } else {
                                FIRST_RUN_SECURITY
                            }
                    }
                )
            }

            /*
             * ---------------------------------------------------------
             * STEP 3 — ACCESSIBILITY DISCLOSURE
             * ---------------------------------------------------------
             */
            FIRST_RUN_DISCLOSURE -> {

                AccessibilityDisclosureScreen {

                    /*
                     * AccessibilityDisclosureScreen already records
                     * the disclosure acceptance and opens Android
                     * Accessibility Settings.
                     *
                     * When the user returns, continue to security
                     * configuration.
                     */
                    repository.refreshProtectionState()

                    firstRunStep =
                        if (
                            repository.authenticationConfigured()
                        ) {
                            FIRST_RUN_DONE
                        } else {
                            FIRST_RUN_SECURITY
                        }
                }
            }

            /*
             * ---------------------------------------------------------
             * STEP 4 — SECURITY / AUTHENTICATION SETUP
             * ---------------------------------------------------------
             */
            FIRST_RUN_SECURITY -> {

                FirstRunSecuritySetupScreen(
                    onComplete = {

                        firstRunStep =
                            FIRST_RUN_DONE
                    }
                )
            }

            /*
             * ---------------------------------------------------------
             * NORMAL APP
             * ---------------------------------------------------------
             */
            FIRST_RUN_DONE -> {

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {

                        AppDrawer(
                            currentDestination =
                                destination,

                            onDestination = {
                                selectedDestination ->

                                destination =
                                    selectedDestination

                                scope.launch {
                                    drawerState.close()
                                }
                            }
                        )
                    }
                ) {

                    Scaffold(
                        topBar = {

                            TopAppBar(
                                title = {
                                    Text(
                                        screenTitle(
                                            destination
                                        )
                                    )
                                },

                                navigationIcon = {

                                    Row(
                                        verticalAlignment =
                                            Alignment.CenterVertically
                                    ) {

                                        IconButton(
                                            onClick = {

                                                scope.launch {
                                                    drawerState.open()
                                                }
                                            }
                                        ) {

                                            Icon(
                                                imageVector =
                                                    Icons.Default.Menu,
                                                contentDescription =
                                                    "Open menu"
                                            )
                                        }

                                        Image(
                                            painter =
                                                AppLockIconPainter(),
                                            contentDescription =
                                                "AppLock logo",
                                            modifier =
                                                Modifier.size(36.dp)
                                        )

                                        Spacer(
                                            modifier =
                                                Modifier.width(8.dp)
                                        )
                                    }
                                }
                            )
                        }
                    ) { paddingValues ->

                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(
                                        paddingValues
                                    )
                        ) {

                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                            ) {

                                when (destination) {

                                    "home" -> {
                                        HomeScreen(
                                            onNavigate = {
                                                target ->
                                                destination =
                                                    target
                                            }
                                        )
                                    }

                                    "apps" -> {
                                        ProtectedAppsScreen()
                                    }

                                    "health" -> {
                                        ProtectionHealthScreen()
                                    }

                                    "smart" -> {
                                        SmartLockScreen()
                                    }

                                    "custom" -> {
                                        CustomizationScreen()
                                    }

                                    "settings" -> {
                                        SettingsScreen()
                                    }

                                    "security" -> {
                                        SecuritySetupScreen()
                                    }

                                    "about" -> {
                                        AboutScreen()
                                    }

                                    else -> {
                                        HomeScreen(
                                            onNavigate = {
                                                target ->
                                                destination =
                                                    target
                                            }
                                        )
                                    }
                                }
                            }

                            if (
                                destination != "pro"
                            ) {

                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .navigationBarsPadding()
                                ) {

                                    BannerAd.Content(
                                        enabled =
                                            adsReady,
                                        modifier =
                                            Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun screenTitle(
    destination: String
): String =
    when (destination) {

        "apps" ->
            "Protected apps"

        "health" ->
            "Protection Health"

        "smart" ->
            "Smart Lock"

        "custom" ->
            "Customization"

        "settings" ->
            "Settings"

        "security" ->
            "Authentication"

        "about" ->
            "About AppLock"

        else ->
            "AppLock – Private App Locker"
    }

private fun Context.findActivity(): Activity? {

    var current: Context = this

    while (current is ContextWrapper) {

        if (current is Activity) {
            return current
        }

        current =
            current.baseContext
    }

    return current as? Activity
}