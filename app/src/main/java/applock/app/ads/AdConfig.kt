package applock.app.ads

object AdConfig {

    const val ADMOB_APP_ID =
        "ca-app-pub-1267608571294570~6976756241"

    const val TEST_BANNER_UNIT_ID =
        "ca-app-pub-3940256099942544/9214589741"

    const val PRODUCTION_BANNER_UNIT_ID =
        "ca-app-pub-1267608571294570/9300656932"

    const val SPONSOR_NAME = "Atoolix"

    const val SPONSOR_URL =
        "https://atoolix.com"

    const val ENABLE_IN_APP_BANNERS = true

    /**
     * TRUE during emulator/device testing.
     *
     * MUST be false for production release.
     */
    const val USE_TEST_ADS = false
}