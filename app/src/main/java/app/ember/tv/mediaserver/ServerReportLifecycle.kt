package app.ember.tv.mediaserver

/** One server session: pause/resume must not create another START, and STOP is balanced. */
class ServerReportLifecycle {
    private var active = false
    fun event(state: ReportState): ReportState? = when (state) {
        ReportState.START -> if (active) ReportState.PROGRESS else { active = true; state }
        ReportState.STOP -> if (!active) null else { active = false; state }
        else -> state.takeIf { active }
    }
}
