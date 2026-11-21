package cz.medsy

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import cz.medsy.ui.MedsyApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // The app holds medication names, doses and lab results. Android's "recent apps"
        // snapshot is taken before ON_STOP can lock the UI, and a screen recorder would
        // otherwise see everything. Always secure the window — this is a privacy-first app.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { false }
        setContent {
            MedsyApp()
        }
    }
}
