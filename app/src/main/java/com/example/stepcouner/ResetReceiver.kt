package com.example.stepcouner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class ResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        StepPrefs.resetDaily(context)
        Toast.makeText(context, "Steps reset Successfully", Toast.LENGTH_SHORT).show()

    }
}
