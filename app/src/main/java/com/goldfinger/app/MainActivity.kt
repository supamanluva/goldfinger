package com.goldfinger.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * Single activity. Hosts the game view in immersive fullscreen and keeps the
 * screen awake (fingers are held down for a while).
 *
 * Optional launch extra "demo" forces a fixed visual state for screenshots, e.g.
 *   adb shell am start -n com.goldfinger.app/.MainActivity --es demo alarm
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Switch from the splash theme to the main (black) theme.
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        val view = GameView(this)
        intent?.getStringExtra("demo")?.let { view.applyDemo(it) }
        setContentView(view)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }
}
