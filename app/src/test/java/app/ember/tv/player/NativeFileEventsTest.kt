package app.ember.tv.player

import dev.jdtech.mpv.MPVLib.MpvEndFileReason
import org.junit.Assert.*
import org.junit.Test

class NativeFileEventsTest {
    @Test fun aFileErrorSchedulesOneDeferredRecovery() {
        val tasks=mutableListOf<Runnable>();var recovered=0
        val events=NativeFileEvents({task,delay->assertEquals(500L,delay);tasks+=task},{true},{recovered++})
        events.started();events.ended(MpvEndFileReason.MPV_END_FILE_REASON_ERROR);events.ended(MpvEndFileReason.MPV_END_FILE_REASON_ERROR)
        assertEquals(1,tasks.size);assertEquals(0,recovered)
        tasks.single().run();assertEquals(1,recovered)
    }
    @Test fun completionStopsShutdownAndRedirectsDoNotBecomePlaybackErrors() {
        val events=NativeFileEvents({_,_->fail("An intentional end must not schedule recovery")},{true},{fail("Unexpected recovery")})
        events.started()
        for(reason in listOf(MpvEndFileReason.MPV_END_FILE_REASON_EOF,MpvEndFileReason.MPV_END_FILE_REASON_STOP,MpvEndFileReason.MPV_END_FILE_REASON_QUIT,MpvEndFileReason.MPV_END_FILE_REASON_REDIRECT))events.ended(reason)
    }
    @Test fun oldErrorsCannotRecoverANewFileOrAStoppedPlayer() {
        val tasks=mutableListOf<Runnable>();var active=true;var recovered=0
        val events=NativeFileEvents({task,_->tasks+=task},{active},{recovered++})
        events.started();events.ended(MpvEndFileReason.MPV_END_FILE_REASON_ERROR)
        events.started();tasks.removeAt(0).run();assertEquals(0,recovered)
        events.ended(MpvEndFileReason.MPV_END_FILE_REASON_ERROR);active=false
        tasks.removeAt(0).run();assertEquals(0,recovered)
        events.started();active=true;events.ended(MpvEndFileReason.MPV_END_FILE_REASON_ERROR)
        tasks.removeAt(0).run();assertEquals(1,recovered)
    }
}
