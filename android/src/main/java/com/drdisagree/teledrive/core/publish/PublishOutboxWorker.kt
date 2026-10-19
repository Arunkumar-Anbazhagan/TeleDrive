package com.drdisagree.teledrive.core.publish

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.drdisagree.teledrive.R
import com.drdisagree.teledrive.core.common.AppNotifications

class PublishOutboxWorker(
    appContext: Context,
    params: WorkerParameters,
    private val drainer: PublishOutboxDrainer,
    private val appNotifications: AppNotifications
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        var channelsReady = false
        val drained = drainer.drain(isStopped = { isStopped }) { published, total ->
            if (total < FOREGROUND_MIN_FILES) return@drain
            if (!channelsReady) {
                appNotifications.createChannels()
                channelsReady = true
            }
            runCatching { setForeground(foregroundInfo(published, total)) }
        }
        return if (drained) Result.success() else Result.retry()
    }

    private fun foregroundInfo(published: Int, total: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(
            applicationContext,
            AppNotifications.CHANNEL_TRANSFERS
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notification_publish_title))
            .setContentText(
                applicationContext.getString(
                    R.string.notification_publish_progress,
                    published,
                    total
                )
            )
            .setProgress(total, published, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                appNotifications.screenIntent(AppNotifications.DESTINATION_FILES, REQUEST_CODE)
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val id = AppNotifications.NOTIFICATION_ID_PUBLISH
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        const val UNIQUE_NAME = "publish-outbox"
        private const val FOREGROUND_MIN_FILES = 10
        private const val REQUEST_CODE = 6
    }
}
