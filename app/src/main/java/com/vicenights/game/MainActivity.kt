package com.vicenights.game

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

class MainActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setContentView(ViceNightsView(this))
    }
    override fun onResume() { super.onResume(); (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.getChildAt(0)?.requestFocus() }
}
