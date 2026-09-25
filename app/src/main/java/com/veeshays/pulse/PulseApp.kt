package com.veeshays.pulse

import android.app.Application
import com.veeshays.pulse.store.PulseStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Cold-start: load persisted state once, off the main thread. */
class PulseApp : Application() {
    lateinit var store: PulseStore
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        store = PulseStore(this)
        scope.launch { store.load() }
    }
}
