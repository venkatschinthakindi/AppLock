# DESIGN LOCK — App Lock Phase 1–5

## Non-negotiable
- Native Kotlin owns the security-critical lock engine.
- Authentication never waits for ads, network, billing, analytics, remote config or downloads.
- Ads never appear on the authentication UI and cannot cover/move security controls.
- AccessibilityService is narrow: detect protected app window transitions; do not retrieve screen content.
- Do not set `isAccessibilityTool=true`; App Lock is not an accessibility tool.
- Prominent disclosure and affirmative consent are required before enabling Accessibility access.
- If the detection mechanism is unavailable, UI must say Limited Protection / Not Protected rather than Protected.
- Never bypass Android security, Play Protect, OEM restrictions or uninstall/system controls.
- No automated tests in this product source package; release verification is manual.

## Navigation
- No bottom tabs.
- Hamburger menu at top-left, familiar to the QR Barcode Studio product family.
- Drawer contains primary/secondary settings and Atoolix sponsor card.

## Visual system
- Central theme source of truth.
- Light / Dark / System.
- Responsive Compose layouts.
- Cosmic Orb default animation, Liquid Flow alternative, Crystal Unlock reserved for Pro.
- Reduced-motion option and no intentional animation delay.

## Monetization
- Pro target: ₹30/month initially, configured through Google Play Billing.
- Production AdMob IDs are retained from QR Barcode Studio source, but banner rendering is feature-gated off until placement/policy review.
- Never force ad viewing/clicking to authenticate.

## Security
- Keystore-backed AES/GCM storage for PIN/pattern secrets.
- `FLAG_SECURE` on the lock activity.
- Lock activity excluded from recents and non-exported.
- No plaintext credential logging.
- Protected-app catalog avoids `QUERY_ALL_PACKAGES`.

## Change-control rule
Before each change: compare it with the master product specification and this file. After each change: re-check authentication latency, battery impact, privacy, Play compliance, theme consistency, and OEM compatibility.

## Review snapshot — 2026-09-11
The original Phase 1–5 source was reviewed after the Compose Color crash. The crash was caused by treating an Android ARGB Long as Compose's packed ULong Color representation. The reviewed source now converts the stored ARGB value through `toInt()`.

The reviewed UI snapshot also replaces the minimal placeholder screens with a coherent responsive Material 3 visual system inspired by the approved AppLock showcase: top-left hamburger navigation, premium status dashboard, app-management filters, health cards, authentication setup, centralized customization, and an ad-free lock surface.
