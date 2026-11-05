package app.ember.tv.data

/** Old profiles keep their household data. New profiles opt into isolated local activity. */
object LocalActivity {
    const val SHARED="shared"
    const val LOCAL="local"
    fun mode(value:String)=if(value==LOCAL)LOCAL else SHARED
    fun scope(profile:Profile)=if(mode(profile.activityMode)==LOCAL)"local:${profile.id}" else SHARED
    fun prefix(scope:String)=if(scope==SHARED)"" else "profile:${scope.length}:$scope:"
    fun scope(context:android.content.Context,profileId:String?=null):String {
        val p=Prefs(context)
        return p.profiles.firstOrNull {it.id==(profileId ?: p.currentProfile)}?.let(::scope) ?: SHARED
    }
}
