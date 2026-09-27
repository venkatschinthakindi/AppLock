package applock.app.security

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * OS-level privacy boundary for Enhanced Protection.
 *
 * Enhanced Protection requires AppLock to be Device Owner.
 *
 * Ordinary Device Admin is intentionally NOT treated as sufficient.
 */
object EnhancedProtectionManager {

    private const val TAG = "AppLockEnhanced"

    enum class Result {
        APPLIED,
        NOT_SUPPORTED,
        NOT_AUTHORIZED,
        INVALID_PACKAGE,
        FAILED
    }

    /**
     * True only when AppLock has the authority required to use
     * DevicePolicyManager package suspension.
     */
    fun hasOsBoundaryAuthority(
        context: Context
    ): Boolean {

        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            ProtectionPolicy.isDeviceOwner(context)
    }

    /**
     * Suspends protected applications at the OS level.
     *
     * A suspended package cannot start activities and does not appear
     * in Android Overview/Recents.
     */
    fun suspendProtectedPackages(
        context: Context,
        packages: Set<String>
    ): Result {

        if (packages.isEmpty()) {
            return Result.INVALID_PACKAGE
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return Result.NOT_SUPPORTED
        }

        if (!ProtectionPolicy.isEnhancedEnforced(context)) {
            Log.w(
                TAG,
                "Refusing suspension because Enhanced Protection " +
                    "is not currently enforced"
            )

            return Result.NOT_AUTHORIZED
        }

        val targets =
            packages
                .filter { it.isNotBlank() }
                .filter { it != context.packageName }
                .distinct()
                .toTypedArray()

        if (targets.isEmpty()) {
            return Result.INVALID_PACKAGE
        }

        val dpm =
            context.getSystemService(
                DevicePolicyManager::class.java
            ) ?: return Result.FAILED

        return runCatching {

            val failed =
                dpm.setPackagesSuspended(
                    ProtectionPolicy.deviceAdminComponent(context),
                    targets,
                    true
                )

            if (failed.isNullOrEmpty()) {

                Log.d(
                    TAG,
                    "OS boundary APPLIED: suspended=" +
                        targets.joinToString()
                )

                Result.APPLIED

            } else {

                Log.e(
                    TAG,
                    "OS boundary FAILED: packages=" +
                        targets.joinToString() +
                        " failed=" +
                        failed.joinToString()
                )

                Result.FAILED
            }

        }.getOrElse { error ->

            Log.e(
                TAG,
                "setPackagesSuspended(true) failed",
                error
            )

            if (error is SecurityException) {
                Result.NOT_AUTHORIZED
            } else {
                Result.FAILED
            }
        }
    }

    /**
     * Temporarily releases the OS boundary for an authenticated launch.
     */
    fun prepareAuthorizedLaunch(
        context: Context,
        packageName: String
    ): Boolean {

        if (packageName.isBlank()) {
            return false
        }

        if (packageName == context.packageName) {
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        if (!hasOsBoundaryAuthority(context)) {

            Log.e(
                TAG,
                "Authorized launch rejected: Device Owner " +
                    "authority unavailable"
            )

            return false
        }

        val dpm =
            context.getSystemService(
                DevicePolicyManager::class.java
            ) ?: return false

        return runCatching {

            val failed =
                dpm.setPackagesSuspended(
                    ProtectionPolicy.deviceAdminComponent(context),
                    arrayOf(packageName),
                    false
                )

            if (failed.isNullOrEmpty()) {

                Log.d(
                    TAG,
                    "OS boundary RELEASED for authorized launch: " +
                        packageName
                )

                true

            } else {

                Log.e(
                    TAG,
                    "Could not release OS boundary for " +
                        packageName +
                        " failed=" +
                        failed.joinToString()
                )

                false
            }

        }.getOrElse { error ->

            Log.e(
                TAG,
                "setPackagesSuspended(false) failed for " +
                    packageName,
                error
            )

            false
        }
    }

    /**
     * Re-establish the OS boundary after returning to AppLock.
     */
    fun reapplyAfterReturnToGate(
        context: Context,
        protectedPackages: Set<String>
    ): Result {

        if (protectedPackages.isEmpty()) {
            return Result.INVALID_PACKAGE
        }

        if (!hasOsBoundaryAuthority(context)) {
            return Result.NOT_AUTHORIZED
        }

        return suspendProtectedPackages(
            context,
            protectedPackages
        )
    }

    /**
     * Re-suspend one protected package.
     */
    fun restoreBoundary(
        context: Context,
        packageName: String
    ): Result {

        if (packageName.isBlank()) {
            return Result.INVALID_PACKAGE
        }

        return suspendProtectedPackages(
            context,
            setOf(packageName)
        )
    }

    /**
     * Check the actual OS suspension state.
     */
    fun isSuspended(
        context: Context,
        packageName: String
    ): Boolean {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        if (!hasOsBoundaryAuthority(context)) {
            return false
        }

        val dpm =
            context.getSystemService(
                DevicePolicyManager::class.java
            ) ?: return false

        return runCatching {
            dpm.isPackageSuspended(
                ProtectionPolicy.deviceAdminComponent(context),
                packageName
            )
        }.getOrDefault(false)
    }

    /**
     * Restore the OS boundary for all currently protected packages.
     */
    fun restoreAllProtectedPackages(
        context: Context,
        protectedPackages: Set<String>
    ): Result {

        if (protectedPackages.isEmpty()) {
            return Result.INVALID_PACKAGE
        }

        return suspendProtectedPackages(
            context,
            protectedPackages
        )
    }

    /**
     * Called when switching away from Enhanced Protection.
     *
     * Actual package unsuspension should happen before the mode is
     * changed to STANDARD so that enhanced enforcement is still true.
     */
    fun disable(
        context: Context
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }

        if (!ProtectionPolicy.isDeviceOwner(context)) {
            return
        }

        Log.d(
            TAG,
            "Enhanced Protection OS boundary disable requested"
        )
    }
}