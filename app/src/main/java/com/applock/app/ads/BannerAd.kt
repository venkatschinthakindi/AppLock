package applock.app.ads

import android.view.ViewGroup
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

object BannerAd {
    fun create(parent: ViewGroup): AdView? {
        if (!AdConfig.ENABLE_IN_APP_BANNERS) return null
        return AdView(parent.context).also {
            it.adUnitId = AdConfig.PRODUCTION_BANNER_UNIT_ID
            it.setAdSize(AdSize.BANNER)
            it.loadAd(AdRequest.Builder().build())
            parent.addView(it)
        }
    }
}
