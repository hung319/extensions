package com.h4rs

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.h4rs.settings.SettingsFragment

@CloudstreamPlugin
class TorraStreamProvider: Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("TorraStream", Context.MODE_PRIVATE)
        torraStreamPrefs = sharedPref
        registerMainAPI(TorraStream(sharedPref))
        registerMainAPI(TorraStreamAnime(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = SettingsFragment(this, sharedPref)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}
