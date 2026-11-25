package app.liquidspot.ui

import android.app.Application

class LiquidSpotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedServiceState.init()
    }
}
