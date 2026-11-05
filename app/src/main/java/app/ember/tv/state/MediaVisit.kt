package app.ember.tv.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** All media requests belong to one visit, even if the next visit has the same title ID. */
internal class MediaVisit(private val parent: CoroutineScope) {
    var scope = newScope(); private set
    private fun newScope() = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    fun reset() { scope.cancel(); scope = newScope() }
    fun cancel() { scope.cancel() }
}
