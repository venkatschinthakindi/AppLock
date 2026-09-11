# AppLock Security Recovery Model

## What this hardening guarantees

- Unlock timestamps are cleared on every cold process start and after device reboot.
- The real Android Accessibility service registry is checked; a stored preference is never treated as proof that the service is enabled.
- Protection Health is recalculated from live Android state.
- Missing authentication configuration is **Not protected** rather than a usable lock state.
- Protected-app selection, session-rule changes, and system/security settings require the existing AppLock credential after credentials have been configured.
- Accessibility service rebind/interrupt resets the process-local lock engine state.
- Android 15+ force-stop recovery is detected on the next AppLock process launch and prior unlock sessions are cleared.
- No code attempts to block uninstall, force-stop, Android Settings, or Accessibility Settings through Accessibility automation.

## Platform boundary

A normal Play-distributed consumer app cannot guarantee protection while its package has been force-stopped or its Accessibility service has been disabled by the device owner. Android owns those controls. The app therefore reports **Not protected** as soon as it can observe the degraded state and never claims that protection is active while the required components are unavailable.

Device-owner/enterprise management is a separate Android deployment model and is not enabled by this consumer AppLock implementation.
