package com.apolito.codexusage.preview

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

class MeterRefreshJob : JobService() {
    private var running: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = params
        // JobScheduler needs the return value before jobFinished can be delivered.
        android.os.Handler(mainLooper).post {
            if (running === params) MeterAccount.backgroundRefresh(this) {
                if (running === params) {
                    running = null
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running = null
        return true
    }

    companion object {
        private const val ID = 11708
        internal const val INTERVAL = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java)
            if (jobs.getPendingJob(ID) != null) return
            val job = JobInfo.Builder(ID, ComponentName(context, MeterRefreshJob::class.java))
                .setPeriodic(INTERVAL)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            if (jobs.schedule(job) != JobScheduler.RESULT_SUCCESS) Log.w("CodexUsageMeter", "Android deferred scheduling")
        }

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(ID)
    }
}
