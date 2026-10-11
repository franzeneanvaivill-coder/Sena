package com.sena.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ACTION_STOP -> Ringer.stop(c)
            else -> Ringer.start(c)
        }
    }

    companion object {
        const val ACTION_RING = "com.sena.app.RING"
        const val ACTION_STOP = "com.sena.app.STOP_ALARM"

        private fun ringIntent(c: Context, flags: Int): PendingIntent? =
            PendingIntent.getBroadcast(
                c, 1,
                Intent(c, AlarmReceiver::class.java).setAction(ACTION_RING),
                flags or PendingIntent.FLAG_IMMUTABLE
            )

        fun schedule(c: Context, at: Long) {
            val am = c.getSystemService(AlarmManager::class.java)
            val show = PendingIntent.getActivity(
                c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), ringIntent(c, PendingIntent.FLAG_UPDATE_CURRENT)!!)
        }

        fun cancel(c: Context) {
            val am = c.getSystemService(AlarmManager::class.java)
            ringIntent(c, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
            Ringer.stop(c)
        }
    }
}
