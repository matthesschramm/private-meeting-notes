package dev.matthes.privatemeetingnotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifications {
    const val RECORDING_ID = 1
    const val PROCESSING_ID = 2
    private const val RECORDING_CHANNEL = "recording"
    private const val PROCESSING_CHANNEL = "processing"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(RECORDING_CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a meeting is being recorded"
                    setShowBadge(false)
                },
                NotificationChannel(PROCESSING_CHANNEL, "Processing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while meetings are transcribed and summarised"
                    setShowBadge(false)
                },
            ),
        )
    }

    /** [recordingSince] is when the recording would have started had it never paused; null while paused. */
    fun recording(context: Context, text: String, recordingSince: Long?, actions: List<Notification.Action>): Notification =
        Notification.Builder(context, RECORDING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recording a meeting")
            .setContentText(text)
            .apply {
                if (recordingSince != null) setWhen(recordingSince).setUsesChronometer(true).setShowWhen(true) else setShowWhen(false)
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .apply { actions.forEach { addAction(it) } }
            .build()

    fun processing(context: Context, text: String, progress: Float?): Notification =
        Notification.Builder(context, PROCESSING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Processing a meeting")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .apply { if (progress != null) setProgress(1000, (progress * 1000).toInt(), false) else setProgress(0, 0, true) }
            .build()

    fun update(context: Context, id: Int, notification: Notification) {
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
