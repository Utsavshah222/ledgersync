package com.ledgersync.app

import android.app.Application
import com.ledgersync.app.sync.SyncScheduler

class LedgerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        SyncScheduler.schedulePeriodic(this)
    }
}
