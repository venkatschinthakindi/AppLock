package applock.app.util

import android.content.Context
import android.content.Intent

object AppIntent {
    fun launch(context: Context, packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { context.startActivity(it) }
    }
}
