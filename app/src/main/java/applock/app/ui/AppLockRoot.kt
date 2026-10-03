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
        rememberDrawerState(DrawerValue.Closed)

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
     * Tracks whether the normal Protection Level screen is currently
     * showing the Device Admin explanation before Android's confirmation UI.
     *
     * This is separate from the first-run Device Admin flow above.
     *
     * NOTE: This state remains for the existing Standard/Device Admin flow.
     * Enhanced Protection no longer uses it.
     */
    var showProtectionAdminDisclosure by remember {
        mutableStateOf(false)
    }

    val lifecycleOwner =
        LocalLifecycleOwner.current

    /*
     * Re-check Device Admin whenever AppLock returns from an Android
     * system screen.
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
     * Protection Mode is required once authentication setup has
     * completed.
     */
    var firstRunStep by remember {

        mutableStateOf(

            when {

                !repository.isOnboardingComplete() ->
                    FIRST_RUN_ONBOARDING

                !FirstRunSetupState
                    .isDeviceAdminDecisionComplete(
                        context
                    ) ->
                    FIRST_RUN_DEVICE_ADMIN

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
     * DEVICE ADMIN RETURN
     * ------------------------------------------------------------------
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
    }

    /*
     * ------------------------------------------------------------------
     * DEVICE ADMIN LAUNCHER
     * ------------------------------------------------------------------
     */
    val deviceAdminLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
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

    /*
     * ------------------------------------------------------------------
     * ENHANCED PROTECTION — DEVICE OWNER PROVISIONING
     * ------------------------------------------------------------------
     *
     * Enhanced Protection is an OS-boundary mode. Device Admin is NOT
     * sufficient authority for it. The existing Device Admin flow above
     * remains untouched for Standard Protection and the first-run
     * tamper-protection decision.
     *
     * Android 12+ requires managed-device provisioning callbacks rather
     * than starting the deprecated ACTION_PROVISION_MANAGED_DEVICE flow
     * from an ordinary already-provisioned app. Therefore this launcher
     * only uses the legacy direct provisioning action on Android versions
     * where that action is still supported by the platform.
     */
    
    val enhancedProvisioningLauncher =
    rememberLauncherForActivityResult(
        contract =
            ActivityResultContracts
                .StartActivityForResult()
    ) {

        /*
         * Never trust RESULT_OK by itself.
         *
         * Device Owner state is the source of truth.
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
        }
    }

    fun beginEnhancedProtection(): Boolean {

        /*
        * If Device Owner already exists, Enhanced Protection can
        * immediately become active.
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

        val activity =
            context.findActivity()
                ?: return false

        /*
        * Android 11 and below:
        *
        * startDeviceOwnerProvisioning() itself starts the legacy
        * provisioning activity.
        *
        * DO NOT call enhancedProvisioningLauncher.launch() after
        * STARTED. That was the double-launch bug in the current code.
        */
        when (
            DeviceOwnerProvisioningManager
                .startDeviceOwnerProvisioning(activity)
        ) {

            DeviceOwnerProvisioningManager
                .ProvisioningStartResult.ALREADY_DEVICE_OWNER -> {

                val enabled =
                    ProtectionPolicy
                        .selectEnhancedIfAvailable(
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
                * The provisioning UI has now been started by the manager.
                *
                * The ActivityResult callback below is only useful for the
                * legacy pre-Android-12 provisioning flow.
                */
                return true
            }

            DeviceOwnerProvisioningManager
                .ProvisioningStartResult.NOT_SUPPORTED_ON_THIS_DEVICE -> {

                /*
                * Android 12+:
                *
                * Do not attempt ACTION_PROVISION_MANAGED_DEVICE here.
                *
                * Device Owner must be established through Android's
                * managed provisioning mechanism.
                *
                * Leave Enhanced Protection unselected.
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
     *
     * Ads are initialized only after first-run security and protection
     * mode setup are complete.
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
                        FIRST_RUN_DEVICE_ADMIN
                }
            }

            /*
             * ==========================================================
             * STEP 2 — DEVICE ADMIN
             * ==========================================================
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
                         * The user explicitly declined Device Admin.
                         * Record the decision so first-run does not
                         * repeatedly stop here.
                         */
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
             * STEP 3 — ACCESSIBILITY DISCLOSURE
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
             * STEP 4 — SECURITY / AUTHENTICATION SETUP
             * ==========================================================
             */
            FIRST_RUN_SECURITY -> {

                FirstRunSecuritySetupScreen(

                    onComplete = {

                        /*
                         * Authentication is now configured.
                         *
                         * Continue to Protection Mode unless a mode
                         * was already selected.
                         */
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
             * STEP 5 — PROTECTION MODE
             * ==========================================================
             *
             * Standard remains the existing production path.
             * Enhanced is now independent from Device Admin and requires
             * actual Device Owner authority before it can be persisted.
             */
            FIRST_RUN_PROTECTION_MODE -> {

                ProtectionModeSelectionScreen(

                    deviceAdminActive =
                        deviceAdminActive,

                    onStandardSelected = {

                        ProtectionPolicy.selectStandard(
                            context
                        )

                        firstRunStep =
                            FIRST_RUN_DONE
                    },

                    onEnhancedSelected = {

                        if (beginEnhancedProtection()) {

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
                                         * Existing Device Admin disclosure is
                                         * retained only for the existing
                                         * Device Admin/Standard path.
                                         *
                                         * Enhanced never routes through this
                                         * screen anymore.
                                         */
                                        if (
                                            showProtectionAdminDisclosure
                                        ) {

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
                                                                "Enable AppLock device protection to strengthen the selected protection mode."
                                                            )
                                                        }

                                                    deviceAdminLauncher
                                                        .launch(intent)
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
                                                     * Enhanced is NOT allowed
                                                     * to fall back to Device
                                                     * Admin. If Device Owner is
                                                     * unavailable, leave the
                                                     * current mode unchanged.
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
