package com.synergic.cloakroom

import android.app.Application
import android.util.Log
import com.synergic.cloakroom.data.seed.DemoSeeder
import com.synergic.cloakroom.data.sync.AutoSync
import com.synergic.cloakroom.di.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CloakRoomApp : Application() {

    @Inject lateinit var seeder: DemoSeeder

    @Inject lateinit var autoSync: AutoSync

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { seeder.ensureSeeded() }
                .onFailure { Log.e("CloakRoomApp", "Seeding demo data failed", it) }
        }
        // Sends check-ins and bills by itself whenever the phone is online.
        autoSync.start()
    }
}
