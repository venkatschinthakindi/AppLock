# Current policy implementation notes

## AccessibilityService

This app uses AccessibilityService because the core App Lock feature needs to detect protected-app window transitions. The service declares no window-content retrieval and does not set `isAccessibilityTool=true`.

The user-facing disclosure is shown in normal app flow before the user is sent to Android Accessibility settings. The Play Console declaration must be completed accurately and re-submitted if the service behavior changes.

## Ads

The lock/authentication surface is deliberately ad-free. In-app banner rendering is disabled by default. Any future placement must be reviewed against current Google Play and AdMob policies before enabling the flag.

## Installed apps

The app list is based on launcher-visible activities rather than `QUERY_ALL_PACKAGES`, reducing package-visibility scope. Validate the resulting list manually on target devices.

## Billing

The security engine does not depend on subscription state. Billing only controls Pro entitlements. Production purchase validation and Play Console product configuration must be verified in closed testing.
