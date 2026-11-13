package org.echoscript.app

import android.app.Application
import android.content.Context
import android.system.Os

/** Configure privacy before any provider, activity or native inference library starts. */
class EchoApplication : Application() {
    override fun attachBaseContext(base: Context) {
        // The runtime API alone cannot prevent its earlier initialization event.
        Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
        check(Os.getenv("ORT_DISABLE_TELEMETRY") == "1") { "Could not disable inference telemetry" }
        super.attachBaseContext(base)
    }
}
