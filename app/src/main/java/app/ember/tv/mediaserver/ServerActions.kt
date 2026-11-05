package app.ember.tv.mediaserver

data class ServerActionState(val watched: Boolean, val favorite: Boolean? = null, val inResume: Boolean = false)
