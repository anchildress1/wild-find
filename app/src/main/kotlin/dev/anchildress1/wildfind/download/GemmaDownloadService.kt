package dev.anchildress1.wildfind.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.StatFs
import android.text.format.Formatter
import android.util.Log
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.download.DownloadFailure
import dev.anchildress1.wildfind.core.download.ModelDownloader
import dev.anchildress1.wildfind.core.download.ModelPin
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Pulls the pinned Gemma file into the app's no-backup model folder as a user-initiated data transfer job.
 *
 * Unlike a foreground service, the system reruns the job after a lost network with the app closed.
 */
class GemmaDownloadService : JobService() {
    // One run per job start; a stopped run's thread can outlive it by a chunk, so each run owns its stop flag.
    private class Run(val params: JobParameters) {
        @Volatile var stopped = false
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val run = Run(params)
        active.set(run)
        Log.i(TAG, "job started")
        val pin = ModelPin.load("gemma")
        // The system requires the job's notification soon after start, before the sideload hash can finish.
        setNotification(params, PROGRESS_ID, progress(0, pin.bytes), JOB_END_NOTIFICATION_POLICY_REMOVE)
        thread(name = TAG) { download(run, pin) }
        return true
    }

    // The network dropped or the system needs the job gone: keep the .part and let the system run it again.
    override fun onStopJob(params: JobParameters): Boolean {
        Log.i(TAG, "job stopped, reason ${params.stopReason}")
        active.getAndSet(null)?.stopped = true
        return true
    }

    // A crash on this thread would take the app's UI down with it, so every failure ends the job here.
    @Suppress("TooGenericExceptionCaught")
    private fun download(run: Run, pin: ModelPin) {
        val downloader = downloader(this, pin)
        var shownPercent = -1
        // Null when the system already stopped this run and will rerun it.
        val reschedule: Boolean? = try {
            downloader.download { done, total ->
                // The blocking pull can't be interrupted; stopping here keeps the .part for the rerun.
                if (run.stopped) throw CancellationException("stopped")
                val percent = percent(done, total)
                if (percent != shownPercent) {
                    shownPercent = percent
                    setNotification(run.params, PROGRESS_ID, progress(done, total), JOB_END_NOTIFICATION_POLICY_REMOVE)
                }
            }
            getSystemService(NotificationManager::class.java).cancel(FAILED_ID)
            false
        } catch (_: CancellationException) {
            null
        } catch (e: DownloadFailure) {
            failed(run, e)
        } catch (e: IOException) {
            // Network errors arrive as HostFailed; this is the local disk.
            stop(e, getString(R.string.download_failed_disk))
        } catch (e: RuntimeException) {
            if (run.stopped) null else stop(e, getString(R.string.download_failed_unexpected))
        }
        active.compareAndSet(run, null)
        if (reschedule != null) {
            Log.i(TAG, "job finished, reschedule $reschedule")
            jobFinished(run.params, reschedule)
        }
    }

    // Returns whether the job should run again, or null when the system already stopped it.
    private fun failed(run: Run, failure: DownloadFailure): Boolean? {
        if (run.stopped) return null
        val size = { bytes: Long -> Formatter.formatShortFileSize(this, bytes) }
        return when (failure) {
            is DownloadFailure.HostFailed -> {
                Log.w(TAG, "download interrupted", failure)
                val status = failure.status
                notifyFailure(
                    if (status == null) {
                        getString(R.string.download_failed_unreachable, failure.host)
                    } else {
                        getString(R.string.download_failed_status, failure.host, status)
                    },
                )
                // The .part survives, so the rerun resumes.
                true
            }

            is DownloadFailure.NotEnoughStorage ->
                stop(failure, getString(R.string.download_failed_space, size(failure.neededBytes)))

            is DownloadFailure.PinMismatch -> stop(failure, getString(R.string.download_failed_pin))

            // A bad hash costs a full 2.6 GB pull, so it never reruns on its own; the next app launch starts over.
            is DownloadFailure.HashMismatch -> stop(failure, getString(R.string.download_failed_hash))
        }
    }

    private fun stop(error: Exception, text: String): Boolean {
        Log.e(TAG, "download stopped", error)
        notifyFailure(text)
        return false
    }

    private fun notifyFailure(text: String) {
        // A parent who declined notifications still gets the download; only the failure text goes unseen.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notification = Notification.Builder(this, channel(this))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.download_failed_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .build()
        getSystemService(NotificationManager::class.java).notify(FAILED_ID, notification)
    }

    private fun progress(done: Long, total: Long): Notification = Notification.Builder(this, channel(this))
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(getString(R.string.download_title))
        .setContentText(
            getString(
                R.string.download_progress,
                Formatter.formatShortFileSize(this, done),
                Formatter.formatShortFileSize(this, total),
            ),
        )
        .setProgress(PERCENT, percent(done, total), false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun percent(done: Long, total: Long) = (done * PERCENT / total).toInt()

    /** Entry point for the app. */
    companion object {
        private const val TAG = "GemmaDownload"
        private const val JOB_ID = 1
        private const val CHANNEL = "model-download"
        private const val PROGRESS_ID = 1
        private const val FAILED_ID = 2
        private const val PERCENT = 100

        // scripts/models.sh sideloads into the same folder.
        private const val MODEL_DIR = "models"

        // The run executing in this process; a job only runs in its app's process, so null means none is running.
        private val active = AtomicReference<Run?>()

        /**
         * Schedules the download now unless a run is in progress; a finished model needs no job.
         *
         * Must run while the app is visible: the system only accepts a user-initiated job then.
         */
        @Suppress("TooGenericExceptionCaught")
        fun start(context: Context) {
            if (active.get() != null) return
            val app = context.applicationContext
            val pin = ModelPin.load("gemma")
            // Off the main thread: a sideloaded model without its marker is hashed once here. A ready model needs
            // no job, so launches after the download don't flash a notification. Scheduling the same id replaces a
            // retry waiting out its backoff, so opening the app restarts a stalled download right away.
            thread(name = TAG) {
                try {
                    if (downloader(app, pin).ready() == null) schedule(app, pin)
                } catch (e: IOException) {
                    Log.e(TAG, "download check failed", e)
                } catch (e: RuntimeException) {
                    // Same as the job thread: a background check must never crash the app's UI.
                    Log.e(TAG, "download check failed", e)
                }
            }
        }

        /**
         * The verified Gemma file, or null until the download finishes.
         *
         * Blocks: a sideloaded model without its marker is hashed once (2.6 GB), so never call it on the main thread.
         */
        fun readyModel(context: Context): File? = downloader(context.applicationContext, ModelPin.load("gemma")).ready()

        private fun schedule(context: Context, pin: ModelPin) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val network = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                // Unmetered alone also matches unmetered cellular; R9 promises Wi-Fi only for 2.6 GB.
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, GemmaDownloadService::class.java))
                .setUserInitiated(true)
                .setRequiredNetwork(network)
                .setEstimatedNetworkBytes(pin.bytes, 0)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) Log.e(TAG, "download job refused")
        }

        private fun downloader(context: Context, pin: ModelPin) =
            ModelDownloader(OkHttpClient(), pin, File(context.noBackupFilesDir, MODEL_DIR), {
                StatFs(context.noBackupFilesDir.path).availableBytes
            })

        private fun channel(context: Context): String {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.download_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            return CHANNEL
        }
    }
}
