package app.ember.tv.data

/** Quick settings > Sleep timer: the player closes when it fires (it watches [fired]). */
object SleepTimer {
    @Volatile var fired: Int = 0
        private set

    fun fire() {
        fired++
    }
}
