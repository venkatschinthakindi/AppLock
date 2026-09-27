package applock.app.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * OS-policy enforcement for Enhanced Protection.
 *
 * Enhanced Protection is considered OS-enforced only when AppLock is the
 * Device Owner. Ordinary Device Admin is deliberately not sufficient.
 *
 * Protected packages are suspended while AppLock's gate/home is active.
 * Android then prevents the package from starting and removes its task from
 * Overview/Recents. An exact authenticated launch temporarily unsuspends
 * the target.
 */
object EnhancedProtectionManager {

    enum class Result {
        APPLIED,
        NOT_DEVICE_OWNER,
        NOT_ENHANCED,
        PARTIAL,
        FAILED
    }

    private const val PREFS = "enhanced_protection_os_policy"
    private const val KEY_MANAGED_PACKAGES = "managed_packages"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

    private fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    private fun admin(context: Context): ComponentName =
        ProtectionPolicy.deviceAdminComponent(context)

    fun isDeviceOwner(context: Context): Boolean =
        runCatching {
            dpm(context)?.isDeviceOwnerApp(context.packageName) == true
        }.getOrDefault(false)

    fun canEnforce(context: Context): Boolean =
        ProtectionPolicy.isEnhancedSelected(context) &&
            isDeviceOwner(context)

    /**
     * Suspends all currently protected packages.
     *
     * The AppLock package itself is always excluded. Android may reject
     * packages such as an active launcher; those are reported as partial
     * application rather than silently treated as secured.
     */
    fun suspendProtectedPackages(
        context: Context,
        protectedPackages: Set<String>
    ): Result {
        if (!ProtectionPolicy.isEnhancedSelected(context)) {
            return Result.NOT_ENHANCED
        }

        val manager = dpm(context) ?: return Result.FAILED

        if (!isDeviceOwner(context)) {
            return Result.NOT_DEVICE_OWNER
        }

        val candidates = protectedPackages
            .asSequence()
            .filter { it.isNotBlank() }
            .filter { it != context.packageName }
            .distinct()
            .toList()

        if (candidates.isEmpty()) {
            persistManagedPackages(context, emptySet())
            return Result.APPLIED
        }

        return runCatching {
            val failed = manager.setPackagesSuspended(
                admin(context),
                candidates.toTypedArray(),
                true
            ).toSet()

            val applied = candidates
                .filterNot(failed::contains)
                .toSet()

            persistManagedPackages(context, applied)

            when {
                failed.isEmpty() -> Result.APPLIED
                applied.isNotEmpty() -> Result.PARTIAL
                else -> Result.FAILED
            }
        }.getOrElse {
            android.util.Log.e(
                "AppLockEnhanced",
                "Failed to suspend protected packages",
                it
            )
            Result.FAILED
        }
    }

    /**
     * Temporarily allows one exact authenticated target to start.
     *
     * This must be called only after LockEngine has granted the exact launch
     * authorization for the same package/request.
     */
    fun prepareAuthorizedLaunch(
        context: Context,
        packageName: String
    ): Boolean {
        if (!canEnforce(context)) {
            return true
        }

        if (
            packageName.isBlank() ||
            packageName == context.packageName
        ) {
            return false
        }

        return runCatching {
            val failed = dpm(context)?.setPackagesSuspended(
                admin(context),
                arrayOf(packageName),
                false
            )?.toSet() ?: return false

            if (failed.isEmpty()) {
                true
            } else {
                android.util.Log.e(
                    "AppLockEnhanced",
                    "OS policy refused to unsuspend $packageName: $failed"
                )
                false
            }
        }.getOrElse {
            android.util.Log.e(
                "AppLockEnhanced",
                "Failed to unsuspend authorized target=$packageName",
                it
            )
            false
        }
    }

    /**
     * Reapply the policy when the gate/home becomes visible again.
     *
     * Suspending the package at this point also removes its Overview/Recents
     * entry according to Android's DevicePolicyManager contract.
     */
    fun reapplyAfterReturnToGate(
        context: Context,
        protectedPackages: Set<String>
    ): Result =
        suspendProtectedPackages(context, protectedPackages)

    /**
     * Removes only package-suspension policy previously applied by AppLock.
     */
    fun disable(context: Context): Result {
        val manager = dpm(context) ?: return Result.FAILED

        if (!isDeviceOwner(context)) {
            persistManagedPackages(context, emptySet())
            return Result.NOT_DEVICE_OWNER
        }

        val packages = managedPackages(context)
        if (packages.isEmpty()) {
            return Result.APPLIED
        }

        return runCatching {
            val failed = manager.setPackagesSuspended(
                admin(context),
                packages.toTypedArray(),
                false
            ).toSet()

            persistManagedPackages(
                context,
                packages - failed
            )

            if (failed.isEmpty()) Result.APPLIED else Result.PARTIAL
        }.getOrElse {
            android.util.Log.e(
                "AppLockEnhanced",
                "Failed to remove Enhanced OS package policy",
                it
            )
            Result.FAILED
        }
    }

    fun isPackageSuspended(
        context: Context,
        packageName: String
    ): Boolean {
        if (!isDeviceOwner(context)) return false

        return runCatching {
            dpm(context)?.isPackageSuspended(
                admin(context),
                packageName
            ) == true
        }.getOrDefault(false)
    }

    fun managedPackages(context: Context): Set<String> =
        prefs(context)
            .getStringSet(KEY_MANAGED_PACKAGES, emptySet())
            ?.toSet()
            ?: emptySet()

    private fun persistManagedPackages(
        context: Context,
        packages: Set<String>
    ) {
        prefs(context)
            .edit()
            .putStringSet(KEY_MANAGED_PACKAGES, packages)
            .apply()
    }
}
