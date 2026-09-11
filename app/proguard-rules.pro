# App Lock release hardening
-keep class applock.app.service.AppDetectionAccessibilityService { *; }
-keep class applock.app.ui.lock.LockActivity { *; }
-keep class applock.app.boot.BootReceiver { *; }
# Do not add broad -keep rules; let R8 shrink ordinary UI/business code.
