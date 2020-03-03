package kr.co.aiblab.test.fcmtopicwithlocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.Message

class LocationReceiver(
    private val handler: Handler
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val location = intent.getParcelableExtra<Location>(LocationUpdatesService.EXTRA_LOCATION)
        handler.sendMessage(Message().apply {
            what = HANDLER_MSG_LOCATION
            obj = location
        })
    }

    companion object {
        const val HANDLER_MSG_LOCATION = 1
    }
}