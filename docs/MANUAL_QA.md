# Manual QA Matrix — App Lock

No automated test code is included. Execute these checks manually on physical devices.

## Core protection
- Enable Accessibility disclosure and consent.
- Select at least three apps from different categories.
- Open each protected app from launcher, recents and another app.
- Confirm lock UI appears quickly and destination content is not intentionally exposed after detection.
- Enter correct PIN/biometric/pattern and confirm destination opens.
- Enter wrong credentials repeatedly; confirm failure does not unlock.
- Press Back; confirm it does not bypass authentication.
- Lock screen / unlock device while a protected app is active.
- Rapidly switch between two protected apps.
- Rotate where supported; confirm no auth state corruption.
- Open App Lock itself; it must never lock itself.

## Sessions
- Test every session rule.
- Verify screen-off behavior.
- Verify timeout boundaries around 1/5/15/30 minutes.
- Manually clear a session by re-locking.

## Reboot/recovery
- Reboot with protected apps configured.
- Confirm the app never displays a false Protected state while Accessibility is unavailable.
- Re-enable service and confirm health recovers.

## Battery/OEM
Run on Pixel/AOSP plus representative Samsung, Xiaomi/Redmi/POCO, OnePlus, Oppo, Vivo/iQOO, Motorola, Realme and Nothing devices where available.
- Check normal battery/background behavior.
- Follow only legitimate OEM settings guidance.
- Do not use hidden/bypass techniques.

## Privacy/security UX
- Verify FLAG_SECURE blocks screenshots/screen recording where expected.
- Check recents preview.
- Check notifications; no credential values must appear.
- Check logcat manually for accidental sensitive logging.
- Disable Accessibility and verify Protection Health changes state.

## Theme/accessibility
- Light/Dark/System.
- Large font / display scaling.
- Reduced motion.
- Small phone, large phone/tablet where supported.
- TalkBack/basic accessibility navigation.

## Ads
- Confirm no ad appears on the lock/authentication surface.
- Confirm ad loading/failure cannot block authentication.
- If ads are enabled later, verify every placement is inside App Lock's own permitted UI and does not resemble security controls.

## Billing
- Closed-test purchase flow with license tester.
- Purchase, pending, cancelled, restored and expired states.
- Confirm Pro only changes monetization/customization entitlements; it never weakens security.

## Release
- Debug and release builds.
- Fresh install, upgrade install and uninstall/reinstall.
- Play closed test install/update.
- Data Safety and Accessibility declaration match actual shipped behavior.
