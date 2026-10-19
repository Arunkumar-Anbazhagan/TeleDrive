package com.drdisagree.teledrive.core.transfer

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.drdisagree.teledrive.R
import com.drdisagree.teledrive.core.common.AppNotifications
import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Expedited with a dataSync foreground service, so long transfers survive app death. */
class TransferQueueWorker(
    appContext: Context,
    params: WorkerParameters,
    private val drainer: TransferQueueDrainer,
    private val settingsRepository: SettingsRepository,
    private val appNotifications: AppNotifications,
    private val transferDao: TransferDao
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!drainer.hasRunnableWork()) return Result.success()
        appNotifications.createChannels()
        runCatching { setForeground(getForegroundInfo()) }

        val result = coroutineScope {
            val progress = launch { showProgress() }
            drainer.drain(
                isStopped = { isStopped },
                onTerminalFailure = { transfer ->
                    if (settingsRepository.preferences.first().failureNotifications) {
                        appNotifications.notifyFailure(
                            title = applicationContext.getString(
                                when (transfer.type) {
                                    TransferType.UPLOAD,
                                    TransferType.BACKUP -> R.string.notification_upload_failed

                                    TransferType.DOWNLOAD,
                                    TransferType.RESTORE -> R.string.notification_download_failed
                                }
                            ),
                            message = transfer.displayName
                        )
                    }
                }
            ).also { progress.cancel() }
        }
        return when (result) {
            TransferDrainResult.COMPLETED -> Result.success()
            TransferDrainResult.INTERRUPTED -> Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(
        baseNotification()
            .setContentTitle(applicationContext.getString(R.string.notification_transfers_active))
            .setProgress(0, 0, true)
            .build()
    )

    private suspend fun showProgress() {
        val seen = mutableMapOf<String, TransferEntity>()
        transferDao.observeActive(PROGRESS_ROW_LIMIT).conflate().collect { active ->
            active.forEach { seen[it.id] = it }
            progressNotification(active, seen.values)?.let { notification ->
                runCatching { setForeground(foregroundInfo(notification)) }
            }
            delay(PROGRESS_INTERVAL_MS.milliseconds)
        }
    }

    private fun progressNotification(
        active: List<TransferEntity>,
        seen: Collection<TransferEntity>
    ): Notification? {
        val running = active.filter { it.state == TransferState.RUNNING }
        if (running.isEmpty()) return null
        val activeIds = active.mapTo(HashSet()) { it.id }
        val finished = seen.filter { it.id !in activeIds }
        val total = seen.size
        val position = (finished.size + 1).coerceAtMost(total)
        val uploading = active.any { it.type.isUpload }
        val downloading = active.any { !it.type.isUpload }

        val title = when {
            uploading && downloading -> applicationContext.getString(
                R.string.notification_transferring_many, position, total
            )

            total == 1 && uploading ->
                applicationContext.getString(R.string.notification_uploading_one)

            total == 1 -> applicationContext.getString(R.string.notification_downloading_one)
            uploading -> applicationContext.getString(
                R.string.notification_uploading_many, position, total
            )

            else -> applicationContext.getString(
                R.string.notification_downloading_many, position, total
            )
        }
        val current = running.first().displayName
        val text = if (running.size > 1) {
            applicationContext.getString(
                R.string.notification_transfer_and_more, current, running.size - 1
            )
        } else {
            current
        }

        val totalBytes = seen.sumOf { it.sizeBytes }
        val doneBytes = finished.sumOf { it.sizeBytes } +
                running.sumOf { it.transferredBytes.coerceAtMost(it.sizeBytes) }
        val percent = if (totalBytes > 0) (doneBytes * 100 / totalBytes).toInt() else 0

        return baseNotification()
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("$percent%")
            .setProgress(100, percent, false)
            .build()
    }

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(applicationContext, AppNotifications.CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                appNotifications.screenIntent(
                    AppNotifications.DESTINATION_TRANSFERS,
                    QUEUE_REQUEST_CODE
                )
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    private fun foregroundInfo(notification: Notification): ForegroundInfo {
        val id = AppNotifications.NOTIFICATION_ID_TRANSFER_QUEUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private val TransferType.isUpload: Boolean
        get() = this == TransferType.UPLOAD || this == TransferType.BACKUP

    companion object {
        const val UNIQUE_NAME = "transfer_queue"
        private const val QUEUE_REQUEST_CODE = 3
        private const val PROGRESS_ROW_LIMIT = 1000
        private const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
