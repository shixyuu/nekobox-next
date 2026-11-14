package io.nekobox.next.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.nekobox.next.data.ProfileRepository
import io.nekobox.next.session.LogBuffer
import java.util.concurrent.TimeUnit

@HiltWorker
class SubscriptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repo: ProfileRepository,
    private val logs: LogBuffer,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID)
        return try {
            if (id == null) repo.refreshAll() else repo.refreshSubscription(id)
            logs.add("订阅已更新")
            Result.success()
        } catch (e: Exception) {
            logs.add("订阅更新失败: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val KEY_ID = "id"
        private const val UNIQUE = "subscription-refresh"

        fun schedule(context: Context, hours: Long = 6) {
            val request = PeriodicWorkRequestBuilder<SubscriptionWorker>(hours, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
