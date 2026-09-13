package applock.app.ads

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import androidx.compose.foundation.layout.padding

object BannerAd {

    private const val TAG = "AppLockBannerAd"

    private fun currentAdUnitId(): String {
        return if (AdConfig.USE_TEST_ADS) {
            AdConfig.TEST_BANNER_UNIT_ID
        } else {
            AdConfig.PRODUCTION_BANNER_UNIT_ID
        }
    }

    /**
     * Legacy ViewGroup API retained for compatibility.
     */
    fun create(parent: ViewGroup): AdView? {
        if (!AdConfig.ENABLE_IN_APP_BANNERS) {
            return null
        }

        return AdView(parent.context).also { adView ->

            adView.adUnitId = currentAdUnitId()

            adView.setAdSize(AdSize.BANNER)

            adView.adListener = object : AdListener() {

                override fun onAdLoaded() {
                    Log.d(TAG, "Banner loaded")
                }

                override fun onAdFailedToLoad(
                    adError: LoadAdError
                ) {
                    Log.w(
                        TAG,
                        "Banner failed: code=${adError.code}, " +
                            "domain=${adError.domain}, " +
                            "message=${adError.message}"
                    )
                }

                override fun onAdImpression() {
                    Log.d(TAG, "Banner impression recorded")
                }
            }

            adView.loadAd(
                AdRequest.Builder().build()
            )

            parent.addView(adView)
        }
    }

    /**
     * Compose banner.
     *
     * The banner is intentionally passive.
     *
     * It never:
     * - controls authentication
     * - delays authentication
     * - determines unlock success
     * - replaces PIN/pattern/biometric
     *
     * The AdView is destroyed when this composable leaves
     * the composition.
     */
    @Composable
    fun Content(
        enabled: Boolean,
        modifier: Modifier = Modifier
    ) {
        if (!enabled || !AdConfig.ENABLE_IN_APP_BANNERS) {
            return
        }

        val context = LocalContext.current
        val configuration = LocalConfiguration.current

        val screenWidthDp =
            configuration.screenWidthDp.coerceAtLeast(320)

        val adView = remember(
            context,
            screenWidthDp
        ) {
            AdView(context).apply {

                adUnitId = currentAdUnitId()

                setAdSize(
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                        context,
                        screenWidthDp
                    )
                )

                adListener = object : AdListener() {

                    override fun onAdLoaded() {
                        Log.d(
                            TAG,
                            "Adaptive banner loaded"
                        )
                    }

                    override fun onAdFailedToLoad(
                        adError: LoadAdError
                    ) {
                        Log.w(
                            TAG,
                            "Adaptive banner failed: " +
                                "code=${adError.code}, " +
                                "domain=${adError.domain}, " +
                                "message=${adError.message}"
                        )
                    }

                    override fun onAdImpression() {
                        Log.d(
                            TAG,
                            "Banner impression recorded"
                        )
                    }
                }
            }
        }

        DisposableEffect(adView) {

            adView.loadAd(
                AdRequest.Builder().build()
            )

            onDispose {
                adView.destroy()
            }
        }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(
                    horizontal = 8.dp,
                    vertical = 4.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                factory = {
                    adView
                },
                update = {
                    // No repeated ad loading.
                }
            )
        }
    }
}