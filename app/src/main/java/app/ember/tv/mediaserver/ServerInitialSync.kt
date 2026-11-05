package app.ember.tv.mediaserver

import app.ember.tv.data.SourceFailure
import app.ember.tv.data.SourceStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Newly authenticated servers can need a moment before their first library request succeeds. */
internal suspend fun initialServerSync(initial:Boolean,load:suspend()->List<SourceStatus>,pause:suspend(Long)->Unit={delay(it)}) {
    repeat(if(initial)3 else 1) {attempt->
        val status=load()
        currentCoroutineContext().ensureActive()
        if(status.none {it.failed && it.failure!=SourceFailure.ACCESS})return
        if(attempt<2 && initial)pause(if(attempt==0)500 else 1500)
    }
}
