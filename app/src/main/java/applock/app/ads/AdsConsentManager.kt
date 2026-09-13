package applock.app.ads

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

class AdsConsentManager(context: Context) {

    private val information: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(
            context.applicationContext
        )

    /**
     * UMP should be refreshed on every application launch.
     *
     * The callback returns whether the Google Mobile Ads SDK
     * is currently allowed to request ads.
     */
    fun requestIfRequired(
        activity: Activity,
        onComplete: (Boolean) -> Unit
    ) {
        val params =
            ConsentRequestParameters.Builder()
                .build()

        information.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform
                    .loadAndShowConsentFormIfRequired(activity) {
                        onComplete(
                            information.canRequestAds()
                        )
                    }
            },
            {
                /*
                 * If the consent information request itself fails,
                 * UMP can still expose the previous consent state.
                 *
                 * We use canRequestAds() rather than assuming consent.
                 */
                onComplete(
                    information.canRequestAds()
                )
            }
        )
    }

    fun canRequestAds(): Boolean =
        information.canRequestAds()
}