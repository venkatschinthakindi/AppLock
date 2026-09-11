package applock.app.ads

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

class AdsConsentManager(context: Context) {
    private val information = UserMessagingPlatform.getConsentInformation(context)
    fun requestIfRequired(activity: Activity, onComplete: (Boolean) -> Unit) {
        val params = ConsentRequestParameters.Builder().build()
        information.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { onComplete(information.canRequestAds()) }
        }, { onComplete(information.canRequestAds()) })
    }
    fun canRequestAds() = information.canRequestAds()
}
