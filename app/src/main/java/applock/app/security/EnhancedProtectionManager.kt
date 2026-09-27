package applock.app.security

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * OS-level privacy boundary for Enhanced Protection.
 *
 * Enhanced Protection requires:
 *
 * 1. AppLock is Device Owner
 * 2. AppLock holds the HOME role
 *
 * Device Admin alone is deliberately NOT sufficient.
 *
 * DevicePolicyManager package suspension is the OS-level
 * protection boundary.
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

    fun hasOsBoundaryAuthority(
        context: Context
    ): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            ProtectionPolicy.isDeviceOwner(context) &&
            ProtectionPolicy.isHomeRoleHeld(context)
    }

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
                "Authorized launch rejected: OS boundary " +
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
     * Disable the OS boundary when Enhanced Protection is
     * no longer active.
     *
     * We intentionally do not attempt to discover arbitrary
     * installed packages here. The active protection snapshot
     * is restored by the service/MainActivity lifecycle before
     * Enhanced Protection is disabled.
     *
     * If there is no Device Owner authority, there is nothing
     * we are allowed to change.
     */
    fun disable(
        context: Context
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }

        if (!hasOsBoundaryAuthority(context)) {
            return
        }

        Log.d(
            TAG,
            "Enhanced Protection OS boundary disable requested"
        )
    }
}