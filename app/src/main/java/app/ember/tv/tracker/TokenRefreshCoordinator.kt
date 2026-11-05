package app.ember.tv.tracker

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by all tracker instances, including the launcher's and player's separate hubs. */
internal object TokenRefreshCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun refresh(
        service: String,
        failedAccess: String?,
        currentAccess: () -> String?,
        renew: suspend () -> Boolean,
    ): Boolean = locks.getOrPut(service) { Mutex() }.withLock {
        val current = currentAccess()
        if (current != failedAccess) !current.isNullOrBlank() else renew()
    }
}
