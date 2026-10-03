package applock.app.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper

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
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import android.graphics.drawable.Drawable

import applock.app.AppLockApplication
import applock.app.ads.AdsConsentManager
import applock.app.ads.BannerAd
import applock.app.security.AppLockDeviceAdminReceiver
import applock.app.security.DeviceOwnerProvisioningManager
import applock.app.security.ProtectionPolicy
import applock.app.ui.components.AppDrawer
import applock.app.ui.screens.AboutScreen
import applock.app.ui.screens.AccessibilityDisclosureScreen
import applock.app.ui.screens.CustomizationScreen
import applock.app.ui.screens.DeviceAdminExplanationScreen
import applock.app.ui.screens.FirstRunSecuritySetupScreen
import applock.app.ui.screens.HomeScreen
import applock.app.ui.screens.OnboardingScreen
import applock.app.ui.screens.ProtectionHealthScreen
import applock.app.ui.screens.ProtectionModeSelectionScreen
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
private const val FIRST_RUN_PROTECTION_MODE = "protection_mode"
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
        rememberDrawerState(
            DrawerValue.Closed
        )

    val scope =
        rememberCoroutineScope()

    var destination by remember(openProtectedApps) {
        mutableStateOf(
            if (openProtectedApps) {
                "apps"
            } else {
                "home"
            }
        )
    }

    /*
     * ------------------------------------------------------------------
     * DEVICE ADMIN
     * ------------------------------------------------------------------
     *
     * Device Admin remains available for the existing Standard/tamper
     * protection functionality.
     *
     * IMPORTANT:
     *
     * Device Admin is NOT part of the mandatory first-run sequence.
     *
     * Installing AppLock or completing onboarding must NOT launch
     * ACTION_ADD_DEVICE_ADMIN automatically.
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

    /*
     * This is retained for the existing Protection Level screen.
     *
     * It is intentionally false during first-run.
     *
     * Nothing in the first-run flow sets this to true.
     */
    var showProtectionAdminDisclosure by remember {
        mutableStateOf(false)
    }

    val lifecycleOwner =
        LocalLifecycleOwner.current

    /*
     * Re-check Device Admin whenever AppLock returns from a system
     * settings/permission screen.
     */
    DisposableEffect(
        lifecycleOwner,
        devicePolicyManager,
        deviceAdminComponent
    ) {

        val observer =
            LifecycleEventObserver { _, event ->

                if (
                    event ==
                    Lifecycle.Event.ON_RESUME
                ) {

                    deviceAdminActive =
                        devicePolicyManager.isAdminActive(
                            deviceAdminComponent
                        )
                }
            }

        lifecycleOwner.lifecycle.addObserver(
            observer
        )

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(
                observer
            )
        }
    }

    /*
     * ------------------------------------------------------------------
     * FIRST-RUN STATE
     * ------------------------------------------------------------------
     *
     * IMPORTANT CHANGE:
     *
     * Device Admin is deliberately NOT checked here.
     *
     * New first-run sequence:
     *
     *   onboarding
     *       ↓
     *   accessibility disclosure
     *       ↓
     *   authentication
     *       ↓
     *   protection mode
     *       ↓
     *   normal app
     *
     * Device Admin is therefore optional and never automatically
     * requested during installation/onboarding.
     */
    var firstRunStep by remember {

        mutableStateOf(

            when {

                !repository.isOnboardingComplete() ->
                    FIRST_RUN_ONBOARDING

                !repository.disclosureAccepted() ->
                    FIRST_RUN_DISCLOSURE

                !repository.authenticationConfigured() ->
                    FIRST_RUN_SECURITY

                !ProtectionModeState.isSelected(
                    context
                ) ->
                    FIRST_RUN_PROTECTION_MODE

                else ->
                    FIRST_RUN_DONE
            }
        )
    }

    /*
     * ------------------------------------------------------------------
     * DEVICE ADMIN LAUNCHER
     * ------------------------------------------------------------------
     *
     * Kept for explicit Device Admin requests elsewhere in AppLock.
     *
     * It is NEVER launched automatically by this composable.
     */
    val deviceAdminLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract =
                androidx.activity.result.contract
                    .ActivityResultContracts
                    .StartActivityForResult()
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

                /*
                 * If this callback happens while first-run is still
                 * active, continue according to the actual first-run
                 * requirements.
                 *
                 * Device Admin itself is NOT a prerequisite anymore.
                 */
                if (
                    firstRunStep ==
                    FIRST_RUN_DEVICE_ADMIN
                ) {

                    firstRunStep =
                        when {

                            !repository.disclosureAccepted() ->
                                FIRST_RUN_DISCLOSURE

                            !repository.authenticationConfigured() ->
                                FIRST_RUN_SECURITY

                            !ProtectionModeState.isSelected(
                                context
                            ) ->
                                FIRST_RUN_PROTECTION_MODE

                            else ->
                                FIRST_RUN_DONE
                        }
                }
            }
        }

    /*
     * ------------------------------------------------------------------
     * ENHANCED PROTECTION
     * ------------------------------------------------------------------
     *
     * Enhanced Protection requires actual Device Owner authority.
     *
     * Device Admin is NOT treated as Device Owner.
     */
    fun beginEnhancedProtection(): Boolean {

        /*
         * First check the real OS authority.
         */
        if (
            ProtectionPolicy.selectEnhancedIfAvailable(
                context
            )
        ) {

            firstRunStep =
                FIRST_RUN_DONE

            showProtectionAdminDisclosure =
                false

            return true
        }

        /*
         * Device Owner does not exist.
         */
        val activity =
            context.findActivity()
                ?: return false

        when (
            DeviceOwnerProvisioningManager
                .startDeviceOwnerProvisioning(
                    activity
                )
        ) {

            DeviceOwnerProvisioningManager
                .ProvisioningStartResult.ALREADY_DEVICE_OWNER -> {

                val enabled =
                    ProtectionPolicy
                        .selectEnhancedIfAvailable(
                            context
                        )

                if (enabled) {

                    firstRunStep =
                        FIRST_RUN_DONE

                    showProtectionAdminDisclosure =
                        false
                }

                return enabled
            }

            DeviceOwnerProvisioningManager
                .ProvisioningStartResult.STARTED -> {

                /*
                 * Legacy pre-Android-12 provisioning has started.
                 *
                 * Do not mark Enhanced as active until the OS confirms
                 * actual Device Owner authority.
                 */
                return true
            }

            DeviceOwnerProvisioningManager
                .ProvisioningStartResult.NOT_SUPPORTED_ON_THIS_DEVICE -> {

                /*
                 * Android 12+ on an already-provisioned device.
                 *
                 * Do not fall back to Device Admin.
                 *
                 * Do not select Enhanced.
                 */
                return false
            }

            DeviceOwnerProvisioningManager
                .ProvisioningStartResult.NOT_ALLOWED,

            DeviceOwnerProvisioningManager
                .ProvisioningStartResult.FAILED -> {

                return false
            }
        }
    }

    /*
     * ------------------------------------------------------------------
     * ADS
     * ------------------------------------------------------------------
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

        if (
            firstRunStep !=
            FIRST_RUN_DONE
        ) {
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

    /*
     * ------------------------------------------------------------------
     * APP THEME
     * ------------------------------------------------------------------
     */
    AppLockTheme(theme) {

        when (firstRunStep) {

            /*
             * ==========================================================
             * STEP 1 — ONBOARDING
             * ==========================================================
             */
            FIRST_RUN_ONBOARDING -> {

                OnboardingScreen {

                    repository.setOnboardingComplete(
                        true
                    )

                    /*
                     * IMPORTANT:
                     *
                     * Previously this went to:
                     *
                     *     FIRST_RUN_DEVICE_ADMIN
                     *
                     * That was the reason Device Admin appeared
                     * immediately after installation.
                     *
                     * It now goes directly to the disclosure step.
                     */
                    firstRunStep =
                        FIRST_RUN_DISCLOSURE
                }
            }

            /*
             * ==========================================================
             * LEGACY DEVICE ADMIN STEP
             * ==========================================================
             *
             * This branch is retained for compatibility with any
             * existing state that may already have reached this step.
             *
             * It is NOT reachable for a fresh installation because
             * the first-run state machine above no longer selects it.
             */
            FIRST_RUN_DEVICE_ADMIN -> {

                DeviceAdminExplanationScreen(

                    onEnableProtection = {

                        val intent =
                            android.content.Intent(
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

                        FirstRunSetupState
                            .markDeviceAdminDecisionComplete(
                                context
                            )

                        firstRunStep =
                            when {

                                !repository.disclosureAccepted() ->
                                    FIRST_RUN_DISCLOSURE

                                !repository.authenticationConfigured() ->
                                    FIRST_RUN_SECURITY

                                !ProtectionModeState.isSelected(
                                    context
                                ) ->
                                    FIRST_RUN_PROTECTION_MODE

                                else ->
                                    FIRST_RUN_DONE
                            }
                    }
                )
            }

            /*
             * ==========================================================
             * STEP 2 — ACCESSIBILITY DISCLOSURE
             * ==========================================================
             */
            FIRST_RUN_DISCLOSURE -> {

                AccessibilityDisclosureScreen {

                    repository.refreshProtectionState()

                    firstRunStep =
                        when {

                            !repository.authenticationConfigured() ->
                                FIRST_RUN_SECURITY

                            !ProtectionModeState.isSelected(
                                context
                            ) ->
                                FIRST_RUN_PROTECTION_MODE

                            else ->
                                FIRST_RUN_DONE
                        }
                }
            }

            /*
             * ==========================================================
             * STEP 3 — SECURITY / AUTHENTICATION SETUP
             * ==========================================================
             */
            FIRST_RUN_SECURITY -> {

                FirstRunSecuritySetupScreen(

                    onComplete = {

                        firstRunStep =
                            if (
                                ProtectionModeState.isSelected(
                                    context
                                )
                            ) {

                                FIRST_RUN_DONE

                            } else {

                                FIRST_RUN_PROTECTION_MODE
                            }
                    }
                )
            }

            /*
             * ==========================================================
             * STEP 4 — PROTECTION MODE
             * ==========================================================
             */
            FIRST_RUN_PROTECTION_MODE -> {

                ProtectionModeSelectionScreen(

                    deviceAdminActive =
                        deviceAdminActive,

                    onStandardSelected = {

                        /*
                         * Standard Protection does NOT request Device
                         * Admin automatically.
                         *
                         * It simply selects Standard mode.
                         */
                        ProtectionPolicy.selectStandard(
                            context
                        )

                        firstRunStep =
                            FIRST_RUN_DONE
                    },

                    onEnhancedSelected = {

                        /*
                         * Enhanced can only become selected if actual
                         * Device Owner authority exists.
                         *
                         * Device Admin is deliberately NOT accepted
                         * as a substitute.
                         */
                        if (
                            beginEnhancedProtection()
                        ) {

                            firstRunStep =
                                FIRST_RUN_DONE
                        }
                    }
                )
            }

            /*
             * ==========================================================
             * NORMAL APP
             * ==========================================================
             */
            FIRST_RUN_DONE -> {

                ModalNavigationDrawer(

                    drawerState =
                        drawerState,

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
                                                Modifier.size(
                                                    36.dp
                                                )
                                        )

                                        Spacer(
                                            modifier =
                                                Modifier.width(
                                                    8.dp
                                                )
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

                                    "protection_mode" -> {

                                        /*
                                         * Existing Protection Level screen.
                                         *
                                         * Device Admin disclosure is only
                                         * shown when explicitly requested
                                         * by some future Standard/tamper
                                         * flow.
                                         */
                                        if (
                                            showProtectionAdminDisclosure
                                        ) {

                                            DeviceAdminExplanationScreen(

                                                onEnableProtection = {

                                                    val intent =
                                                        android.content.Intent(
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
                                                                "Enable AppLock device protection to strengthen the selected protection mode."
                                                            )
                                                        }

                                                    deviceAdminLauncher
                                                        .launch(
                                                            intent
                                                        )
                                                },

                                                onNotNow = {

                                                    showProtectionAdminDisclosure =
                                                        false
                                                }
                                            )

                                        } else {

                                            ProtectionModeSelectionScreen(

                                                deviceAdminActive =
                                                    deviceAdminActive,

                                                onStandardSelected = {

                                                    ProtectionPolicy
                                                        .selectStandard(
                                                            context
                                                        )
                                                },

                                                onEnhancedSelected = {

                                                    /*
                                                     * Never fall back to
                                                     * Device Admin.
                                                     */
                                                    beginEnhancedProtection()
                                                }
                                            )
                                        }
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

        "protection_mode" ->
            "Protection Level"

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