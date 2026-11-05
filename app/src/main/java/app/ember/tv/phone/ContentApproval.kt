package app.ember.tv.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

/** A proposal never writes content until the TV approves it during the originating lease/profile. */
internal class ContentApproval {
    data class Pending(val summary: String, val active: () -> Boolean, val apply: suspend () -> String?)
    var pending by mutableStateOf<Pending?>(null); private set
    var busy by mutableStateOf(false); private set
    @Synchronized fun stage(summary: String, active: () -> Boolean, apply: suspend () -> String?): String {
        if (pending != null || busy) return "Review the pending change on the TV first."
        if (!active()) return "The phone session expired. No change was applied."
        pending = Pending(summary, active, apply)
        return "Sent to the TV. Choose Approve or Reject in Phone setup."
    }
    @Synchronized fun clear() { pending = null }
    suspend fun approve(): String {
        val proposal = synchronized(this) {
            if (busy) return "A content change is already being saved."
            val value = pending ?: return "No content change is pending."
            busy = true
            value
        }
        return try {
            if (!proposal.active()) "The phone session expired. No change was applied."
            else proposal.apply() ?: "Content change saved."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { "Could not apply this change. Check the source and try again." }
        finally { synchronized(this) { if (pending === proposal) pending = null; busy = false } }
    }
}
