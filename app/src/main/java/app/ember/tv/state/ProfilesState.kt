package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.AppEntry
import app.ember.tv.data.CatalogDef
import app.ember.tv.data.Prefs
import app.ember.tv.data.Profile
import app.ember.tv.data.KidsContentPolicy

/**
 * Settings > Profiles and "Who's watching?". Split out of HomeViewModel. [catalogs] and [apps]
 * seed a new Kids profile; [onChanged] reloads Home for the current profile.
 */
class ProfilesState(
    private val prefs: Prefs,
    private val catalogs: () -> List<CatalogDef>,
    private val apps: () -> List<AppEntry>,
    private val withPin: (String, () -> Unit) -> Unit,
    private val onNotice: (String) -> Unit,
    private val onChanged: () -> Unit,
) {
    internal var onRemoved:(String)->Unit={}
    var all by mutableStateOf(prefs.profiles); private set
    var profileId by mutableStateOf(prefs.currentProfile.takeIf {id->all.any {it.id==id}} ?: all.first().id.also {prefs.currentProfile=it}); private set
    val profile: Profile
        get() = all.firstOrNull { it.id == profileId } ?: all.first()

    /** Personal rows (Continue watching, Up next, watchlist, tracker and server rows) are shown. */
    val personalOn: Boolean get() = profile.personal && !profile.kids

    fun allows(c: CatalogDef): Boolean = app.ember.tv.data.ProfileCatalogs.allows(profile, c)
    fun allowsApp(pkg: String): Boolean = profile.apps?.let { pkg in it } ?: true

    var setupRequested by mutableStateOf<String?>(null)
    var showProfilePicker by mutableStateOf(false)
    /** Manual avatar popup is separate from the optional startup chooser. */
    var showProfileMenu by mutableStateOf(false)
    var askProfileAtStart by mutableStateOf(prefs.askProfile); private set

    fun toggleAskProfileAtStart() {
        askProfileAtStart = !askProfileAtStart
        prefs.askProfile = askProfileAtStart
    }

    /** Called when the launcher starts: "Who's watching?" when that is on and there is a choice. */
    fun askProfileIfWanted() {
        if (askProfileAtStart && all.size > 1) showProfilePicker = true
    }

    private fun saveProfiles(list: List<Profile>) {
        all = list
        prefs.profiles = list
    }

    /** Switches profile; leaving a Kids profile for another asks for the PIN. */
    fun switchProfile(id: String) {
        val target = all.firstOrNull { it.id == id } ?: return
        if(!profile.kids && target.kids && (target.kidsSetupRequired || prefs.pinHash.isNullOrBlank())) {
            setupRequested=target.id;showProfilePicker=false
            return
        }
        val go = {
            profileId = id
            prefs.currentProfile = id
            app.ember.tv.data.ArtworkCaches.accessChanged()
            showProfilePicker = false
            onNotice("Profile: ${target.name}")
            onChanged()
        }
        if (KidsContentPolicy.switchNeedsPin(profile,target)) withPin("Enter the PIN to leave ${profile.name}", go) else go()
    }

    fun addProfile(kids: Boolean): String {
        val id = java.util.UUID.randomUUID().toString().take(8)
        val p = if (kids) {
            Profile(
                id = id,
                name = "Kids",
                kids = true,
                personal = false,
                live = false,
                // A starting point: catalogs whose names sound like children's or family ones.
                catalogs = catalogs().filter { Profile.KIDS_WORDS.containsMatchIn(it.label) }.map { it.key }.toSet(),
                // Every app except ones that change the TV or open the internet; adjust in Settings > Profiles.
                apps = apps().map { it.pkg }.filterNot { pkg -> Profile.KIDS_BLOCKED.containsMatchIn(pkg) }.toSet(),
                color = 2,
                kidsSetupRequired=true,
            )
        } else {
            Profile(id = id, name = "Profile ${all.size + 1}", color = all.size % Profile.COLORS.size)
        }
        saveProfiles(all + p.copy(activityMode=app.ember.tv.data.LocalActivity.LOCAL))
        return id
    }

    fun completeKidsSetup(value:Profile):Boolean {
        if(prefs.pinHash.isNullOrBlank()) {onNotice("Set the administrator PIN first.");return false}
        val ready=value.copy(kids=true,personal=false,kidsSetupComplete=true,kidsSetupRequired=false,activityMode=app.ember.tv.data.LocalActivity.LOCAL)
        if(all.any {it.id==ready.id})updateProfile(ready.id){ready} else saveProfiles(all+ready)
        setupRequested=null
        onNotice("Kids profile ready. Leaving it requires the administrator PIN.")
        return true
    }

    fun updateProfile(id: String, change: (Profile) -> Profile) {
        val original=all.firstOrNull {it.id==id} ?: return
        val updated=change(original)
        if(KidsContentPolicy.restricted(updated) && prefs.pinHash.isNullOrBlank()) {
            onNotice("Set a PIN before enabling a Kids content lock.")
            return
        }
        saveProfiles(all.map { if (it.id == id) updated else it })
        if (id == profileId) onChanged()
    }

    fun deleteProfile(id: String) {
        val remaining = all.filterNot { it.id == id }
        if (remaining.isEmpty() || remaining.size == all.size) return
        saveProfiles(remaining)
        onRemoved(id)
        app.ember.tv.data.ArtworkCaches.accessChanged()
        if (id == profileId) {
            profileId = all.first().id
            prefs.currentProfile = profileId
            onChanged()
        }
    }
}
