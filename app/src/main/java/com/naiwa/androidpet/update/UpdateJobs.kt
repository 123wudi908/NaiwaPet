package com.naiwa.androidpet.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

object UpdateJobs {
    private const val JOB_ID = 0x4e57
    fun configure(context: Context, enabled: Boolean): Boolean {
        return try {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!enabled) { scheduler.cancel(JOB_ID); return true }
            if (scheduler.getPendingJob(JOB_ID) != null) return true
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(24 * 60 * 60 * 1000L)
                .setPersisted(true)
                .build()
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        } catch (_: RuntimeException) { false }
    }
}

class UpdateJobService : JobService() {
    private var stopped = AtomicBoolean(false)
    private var worker: Thread? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val assignedNetwork = if (Build.VERSION.SDK_INT >= 28) params.network else null
        if (Build.VERSION.SDK_INT >= 28 && assignedNetwork == null) return false
        val jobTransport = assignedNetwork?.let { network -> GitHubHttpTransport { url -> network.openConnection(url) } }
        val cancelled = AtomicBoolean(false); stopped = cancelled
        worker = Thread {
            try { UpdateManager.get(this).checkFromJob(jobTransport) { !cancelled.get() } }
            finally { if (!cancelled.get()) jobFinished(params, false) }
        }.apply { name = "naiwa-update-job"; start() }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        stopped.set(true); worker?.interrupt(); worker = null
        return false
    }
}
