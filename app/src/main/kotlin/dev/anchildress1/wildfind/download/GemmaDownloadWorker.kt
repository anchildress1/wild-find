package dev.anchildress1.wildfind.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.download.DownloadFailure
import dev.anchildress1.wildfind.core.download.ModelDownloader
import dev.anchildress1.wildfind.core.download.ModelPin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Pulls the pinned Gemma file into the app's no-backup model folder on unmetered networks, in the foreground. */
class GemmaDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = NotificationManagerCompat.from(context)
    private var shownPercent = -1

    override suspend fun doWork(): Result {
        val context = applicationContext
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.download_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val pin = ModelPin.load("gemma")
        val downloader = ModelDownloader(OkHttpClient(), pin, File(context.noBackupFilesDir, MODEL_DIR), {
            StatFs(context.noBackupFilesDir.path).availableBytes
        })
        return try {
            withContext(Dispatchers.IO) {
                if (downloader.isReady()) return@withContext Result.success()
                try {
                    setForeground(foregroundInfo(0, pin.bytes))
                } catch (e: IllegalStateException) {
                    // Android 12+ refuses a foreground start from the background (a retry after a Wi-Fi drop). Running
                    // on as plain work would leave WorkManager ignoring stops for a worker it marked foreground, so
                    // wait: the next app launch replaces this retry with a run that can start in the foreground.
                    Log.w(TAG, "foreground start refused; waiting for the app to open", e)
                    return@withContext Result.retry()
                }
                downloader.download { done, total ->
                    // The blocking pull can't see coroutine cancellation; stopping here keeps the .part for a resume.
                    if (isStopped) throw CancellationException("stopped")
                    progress(done, total)
                }
                notifications.cancel(FAILED_ID)
                Result.success()
            }
        } catch (e: DownloadFailure) {
            failed(e)
        } catch (e: IOException) {
            // A Wi-Fi drop breaks the socket before the next progress check sees the stop; that's no failure.
            if (isStopped) Result.retry() else stop(e, context.getString(R.string.download_failed_disk))
        }
    }

    private fun failed(failure: DownloadFailure): Result {
        val context = applicationContext
        val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
        return when (failure) {
            is DownloadFailure.HostFailed -> if (isStopped) {
                Result.retry()
            } else {
                Log.w(TAG, "download interrupted, attempt ${runAttemptCount + 1}", failure)
                val status = failure.status
                notifyFailure(
                    if (status == null) {
                        context.getString(R.string.download_failed_unreachable, failure.host)
                    } else {
                        context.getString(R.string.download_failed_status, failure.host, status)
                    },
                )
                // The .part survives, so a retry resumes; WorkManager's backoff caps the wait at 5 hours.
                Result.retry()
            }

            is DownloadFailure.NotEnoughStorage ->
                stop(failure, context.getString(R.string.download_failed_space, size(failure.neededBytes)))

            is DownloadFailure.PinMismatch -> stop(failure, context.getString(R.string.download_failed_pin))

            // A bad hash costs a full 2.6 GB pull, so it never retries on its own; the next app launch starts over.
            is DownloadFailure.HashMismatch -> stop(failure, context.getString(R.string.download_failed_hash))
        }
    }

    private fun stop(error: Exception, text: String): Result {
        Log.e(TAG, "download stopped", error)
        notifyFailure(text)
        return Result.failure()
    }

    private fun notifyFailure(text: String) {
        val context = applicationContext
        // A parent who declined notifications still gets the download; only the failure text goes unseen.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.download_failed_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .build()
            notifications.notify(FAILED_ID, notification)
        }
    }

    private fun progress(done: Long, total: Long) {
        val percent = percent(done, total)
        if (percent == shownPercent) return
        shownPercent = percent
        setForegroundAsync(foregroundInfo(done, total))
    }

    private fun foregroundInfo(done: Long, total: Long): ForegroundInfo {
        val context = applicationContext
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.download_title))
            .setContentText(
                context.getString(
                    R.string.download_progress,
                    Formatter.formatShortFileSize(context, done),
                    Formatter.formatShortFileSize(context, total),
                ),
            )
            .setProgress(PERCENT, percent(done, total), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun percent(done: Long, total: Long) = (done * PERCENT / total).toInt()

    /** Entry point for the app. */
    companion object {
        private const val TAG = "GemmaDownload"
        private const val WORK = "gemma-download"
        private const val CHANNEL = "model-download"
        private const val PROGRESS_ID = 1
        private const val FAILED_ID = 2
        private const val PERCENT = 100

        // scripts/models.sh sideloads into the same folder.
        private const val MODEL_DIR = "models"

        /** Starts the download now unless a pull is already running; a finished model returns at once. */
        fun start(context: Context) {
            val manager = WorkManager.getInstance(context)
            val existing = manager.getWorkInfosForUniqueWork(WORK)
            existing.addListener({
                // A retry waiting out its backoff can't go foreground from the background, so an app launch, when a
                // foreground start is allowed, replaces it with a run that starts now. A running pull is left alone.
                if (existing.get().none { it.state == WorkInfo.State.RUNNING }) {
                    val request = OneTimeWorkRequestBuilder<GemmaDownloadWorker>()
                        .setConstraints(Constraints(requiredNetworkType = NetworkType.UNMETERED))
                        .build()
                    manager.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }
}
