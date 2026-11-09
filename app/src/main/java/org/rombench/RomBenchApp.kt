package org.rombench

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.rombench.core.ResultStore

class RomBenchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ResultStore.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TESTS, getString(R.string.channel_tests), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        /** General channel for test notifications (areas may create their own, e.g. for the LED). */
        const val CHANNEL_TESTS = "tests"
    }
}
