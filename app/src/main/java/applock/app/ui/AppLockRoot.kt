package applock.app.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable

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

    val repository =
        app.repository

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
     * Existing explicit Device Admin disclosure state.
     *
     * This is NOT automatically enabled by first-run.
     */
    var showProtectionAdminDisclosure by remember {

        mutableStateOf(false)
    }
    /*
 * ------------------------------------------------------------------
 * ENHANCED PROVISIONING UI STATE
 * ------------------------------------------------------------------
 */

var enhancedProvisioningInFlight by remember {
    mutableStateOf(false)
}

var enhancedProvisioningUnavailable by remember {
    mutableStateOf(false)
}

/*
 * ------------------------------------------------------------------
 * FIRST-RUN STATE
 * ------------------------------------------------------------------
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

            !ProtectionModeState.isSelected(context) ->
                FIRST_RUN_PROTECTION_MODE

            else ->
                FIRST_RUN_DONE
        }
    )
}

    /*
     * ------------------------------------------------------------------
     * ENHANCED PROVISIONING UI STATE
     * ------------------------------------------------------------------
     *
     * enhancedProvisioningInFlight:
     *
     * Prevents repeated calls to DeviceOwner provisioning while the
     * provisioning operation is already in progress.
     *
     * enhancedProvisioningUnavailable:
     *
     * Prevents the UI from repeatedly attempting an operation that
     * Android has already told us cannot be started on this device.
     */
    

    val lifecycleOwner =
        LocalLifecycleOwner.current

    /*
     * ------------------------------------------------------------------
     * LIFECYCLE / DEVICE ADMIN / DEVICE OWNER RECHECK
     * ------------------------------------------------------------------
     *
     * Device Admin is refreshed whenever AppLock resumes.
     *
     * If legacy Device Owner provisioning was started, we also use
     * ON_RESUME to determine whether the OS actually granted Device
     * Owner authority.
     *
     * There is deliberately NO enhancedProvisioningLauncher here.
     *
     * DeviceOwnerProvisioningManager directly calls
     * Activity.startActivityForResult(), so an ActivityResultLauncher
     * in this composable would not receive that result.
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

                    /*
                     * Only inspect the provisioning result if we actually
                     * started a legacy provisioning operation.
                     */
                    if (
                        enhancedProvisioningInFlight
                    ) {

                        if (
                            ProtectionPolicy.isDeviceOwner(
                                context
                            )
                        ) {

                            /*
                             * Device Owner was successfully granted.
                             */
                            enhancedProvisioningInFlight =
                                false

                            enhancedProvisioningUnavailable =
                                false

                            if (
                                ProtectionPolicy
                                    .selectEnhancedIfAvailable(
                                        context
                                    )
                            ) {

                                firstRunStep =
                                    FIRST_RUN_DONE

                                showProtectionAdminDisclosure =
                                    false
                            }

                        } else {

                            /*
                             * We returned from provisioning without
                             * obtaining Device Owner.
                             *
                             * Do not automatically retry.
                             */
                            enhancedProvisioningInFlight =
                                false

                            enhancedProvisioningUnavailable =
                                true
                        }
                    }
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
    
    fun beginEnhancedProtection(): Boolean {

    // Prevent repeated taps/calls while provisioning is already being
    // attempted, or after Android has told us this device cannot perform
    // the requested provisioning flow.
    if (
        enhancedProvisioningInFlight ||
        enhancedProvisioningUnavailable
    ) {
        return false
    }

    /*
     * If AppLock already has real Device Owner authority, enable
     * Enhanced Protection immediately.
     */
    if (
        ProtectionPolicy.selectEnhancedIfAvailable(
            context
        )
    ) {
        firstRunStep = FIRST_RUN_DONE
        showProtectionAdminDisclosure = false
        return true
    }

    /*
     * No Device Owner yet.
     */
    val activity =
        context.findActivity()
            ?: return false

    enhancedProvisioningInFlight = true

    when (
        DeviceOwnerProvisioningManager
            .startDeviceOwnerProvisioning(
                activity
            )
    ) {

        DeviceOwnerProvisioningManager
            .ProvisioningStartResult.ALREADY_DEVICE_OWNER -> {

            enhancedProvisioningInFlight = false

            val enabled =
                ProtectionPolicy.selectEnhancedIfAvailable(
                    context
                )

            if (enabled) {
                firstRunStep = FIRST_RUN_DONE
                showProtectionAdminDisclosure = false
            }

            return enabled
        }

        DeviceOwnerProvisioningManager
            .ProvisioningStartResult.STARTED -> {

            /*
             * The OS provisioning flow has actually started.
             *
             * Do not mark Enhanced as active yet. Device Owner
             * authority must be confirmed after provisioning returns.
             */
            return true
        }

        DeviceOwnerProvisioningManager
            .ProvisioningStartResult.NOT_SUPPORTED_ON_THIS_DEVICE,

        DeviceOwnerProvisioningManager
            .ProvisioningStartResult.NOT_ALLOWED,

        DeviceOwnerProvisioningManager
            .ProvisioningStartResult.FAILED -> {

            enhancedProvisioningInFlight = false
            enhancedProvisioningUnavailable = true

            return false
        }
    }
}
    /*
     * ------------------------------------------------------------------
     * DEVICE ADMIN LAUNCHER
     * ------------------------------------------------------------------
     *
     * Retained for explicit Device Admin requests elsewhere in AppLock.
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

                    firstRunStep =
                        FIRST_RUN_DISCLOSURE
                }
            }

            /*
             * ==========================================================
             * LEGACY DEVICE ADMIN STEP
             * ==========================================================
             *
             * Retained only for compatibility with existing state.
             *
             * Fresh installations do NOT enter this step.
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

                    deviceOwnerActive =
                        ProtectionPolicy.isDeviceOwner(
                            context
                        ),

                    enhancedProvisioningInFlight =
                        enhancedProvisioningInFlight,

                    enhancedProvisioningUnavailable =
                        enhancedProvisioningUnavailable,

                    onStandardSelected = {

                        enhancedProvisioningInFlight =
                            false

                        enhancedProvisioningUnavailable =
                            false

                        ProtectionPolicy.selectStandard(
                            context
                        )

                        firstRunStep =
                            FIRST_RUN_DONE
                    },

                    onEnhancedSelected = {

                        beginEnhancedProtection()
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

                                                deviceOwnerActive =
                                                    ProtectionPolicy
                                                        .isDeviceOwner(
                                                            context
                                                        ),

                                                enhancedProvisioningInFlight =
                                                    enhancedProvisioningInFlight,

                                                enhancedProvisioningUnavailable =
                                                    enhancedProvisioningUnavailable,

                                                onStandardSelected = {

                                                    enhancedProvisioningInFlight =
                                                        false

                                                    enhancedProvisioningUnavailable =
                                                        false

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