package kanami.isa.opencode.connect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object Notifier {

    private const val CHANNEL_ID = "opencode_events"
    private const val CHANNEL_NAME = "Agent events"
    private const val NOTIF_FINISHED = 1001
    private const val NOTIF_QUESTION = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifies when the desktop agent finishes a response"
        }
        mgr.createNotificationChannel(channel)
    }

    fun notifyFinished(context: Context, title: String, text: String, sessionId: String) =
        post(context, sessionId, title, text, NOTIF_FINISHED)

    /** The desktop agent is waiting for an answer to a question. */
    fun notifyQuestion(context: Context, title: String, text: String, sessionId: String) =
        post(context, sessionId, title, text, NOTIF_QUESTION)

    private fun post(context: Context, sessionId: String, title: String, text: String, notifId: Int) {
        ensureChannel(context)
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tap = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        }
        val pending = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { mgr.notify(notifId, notification) }
    }
}
