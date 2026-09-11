# App Lock — Production Phase 1–5 Kotlin Source

This package is the structured native Kotlin/Jetpack Compose implementation covering the requested Phase 1–5 product architecture. It is intentionally delivered as source code so the developer can perform the required Android Studio/device/manual QA and Play Console release work.

## What is included

- Native Kotlin lock engine separated from UI.
- AccessibilityService used narrowly for protected-app window detection; window content retrieval is disabled.
- Prominent in-app Accessibility disclosure + affirmative consent flow.
- Secure window flags and no recent-task lock screen activity.
- PIN, pattern model, and Android BiometricPrompt path.
- Keystore AES/GCM secret storage; no plaintext PIN logging.
- Protected-app catalog based on launcher-visible apps; no QUERY_ALL_PACKAGES.
- Session rules and manual protection health diagnostics.
- Centralized Light/Dark/System theme model, accent, radius, animation style and reduced motion.
- Hamburger navigation at the top-left; no bottom navigation.
- Atoolix sponsor card in the drawer.
- AdMob production App ID and banner unit carried from the user's QR Barcode Studio production configuration, while in-app banner rendering remains disabled until the final placement/policy review.
- UMP consent boundary.
- Google Play Billing subscription boundary with product ID `app_lock_pro_monthly`.
- OEM/battery diagnostics without bypassing Android restrictions.
- Boot recovery receiver that never falsely claims protection before the service is available.
- Release signing/CI template and manual QA/release documentation.
- No automated test source, test suites, integration-test fixtures, or generated test code.

## Important production boundary

This is production-oriented source, not a certification that every OEM behavior or Play review outcome has been verified. AccessibilityService behavior, no-flash transitions, Samsung/Xiaomi/OnePlus/Oppo/Vivo/Realme/Motorola/Nothing background behavior, biometric UX, billing, and final ad placement require the manual device/closed-test process you said you will perform.

Do not ship ads on the authentication UI. `AdConfig.ENABLE_IN_APP_BANNERS` is intentionally false until the exact monetization surfaces are reviewed against current Play and AdMob policy.

## Build

Open the root folder in Android Studio with JDK 21 and Android SDK 36 installed. Sync Gradle, then run `assembleDebug` and install to a physical test device. This session does not have a configured Android SDK/Gradle cache, so a successful Android build was not honestly claimed here.

## First release configuration you must complete

1. Final application ID is `applock.app`.
2. Create the Play subscription product `app_lock_pro_monthly` and verify its base plan/offer in Play Console.
3. Configure your release/upload keystore through the CI secrets template; never put passwords or keystores in this repository.
4. Complete the AccessibilityService declaration and Data Safety forms with wording that exactly matches the shipped implementation.
5. Host/review the Privacy Policy and confirm support/contact details.
6. Complete AdMob app confirmation/readiness and consent configuration before enabling ads.
7. Run the manual release matrix in `docs/MANUAL_QA.md` on multiple Android versions and OEMs.
8. Run a closed test and review Play pre-launch/device reports before production rollout.

## Product rule

Authentication must remain local-first and independent of ads, network, analytics, billing, remote config and theme downloads.
