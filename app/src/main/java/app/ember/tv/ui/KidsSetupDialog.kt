package app.ember.tv.ui

import androidx.compose.runtime.*
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.Profile
import app.ember.tv.data.KidsContentPolicy
import app.ember.tv.data.LocalActivity

/** Nothing is activated until the parent reviews policy and proves the return PIN. */
@Composable internal fun KidsSetupDialog(vm:HomeViewModel,existing:Profile?=null,close:()->Unit,onReady:(String)->Unit={}) {
    var draft by remember(existing?.id) {mutableStateOf(existing ?: Profile(
        java.util.UUID.randomUUID().toString().take(8),"Kids",kids=true,personal=false,live=false,color=2,
        activityMode=LocalActivity.LOCAL,kidsSetupRequired=true,
        catalogs=vm.allCatalogs.filter {Profile.KIDS_WORDS.containsMatchIn(it.label)}.map {it.key}.toSet(),
        apps=vm.apps.filterNot {Profile.KIDS_BLOCKED.containsMatchIn(it.pkg)}.map {it.pkg}.toSet()))}
    var stage by remember {mutableIntStateOf(0)}
    var verified by remember {mutableStateOf(false)}
    var verifiedRevision by remember {mutableIntStateOf(-1)}
    if(stage==1 && !vm.pin.pinSet) {
        PinPad("Choose the administrator PIN",onDone={vm.pin.setPin(it);stage=2;true},onCancel={stage=0},vm=vm)
        return
    }
    if(stage==2) {
        PinPad("Test return to the parent profile",onDone={pin->
            val ok=vm.pin.verifyPin(pin)
            if(ok){verified=true;verifiedRevision=vm.pin.revision;stage=3};ok
        },onCancel={stage=0},vm=vm)
        return
    }
    MediaDialog(if(stage==3)"Kids profile ready" else "Set up a Kids profile",close,resizeForKeyboard=true) {
        if(stage==3) {
            Text("Administrator PIN verified. ${draft.name} has local playback history, restricted app access and PIN-protected settings.")
            Note(KidsContentPolicy.summary(draft))
            Chip("Save Kids profile") {if(verified && verifiedRevision==vm.pin.revision && vm.profiles.completeKidsSetup(draft)){onReady(draft.id);close()}}
            if(verifiedRevision!=vm.pin.revision)Chip("Verify administrator PIN again") {stage=2}
            Chip("Review choices") {stage=0;verified=false}
        } else {
            TextInput(draft.name,{draft=draft.copy(name=it.take(60))},"Profile name")
            KidsContentSettings(draft){change->draft=change(draft)}
            Note("${draft.apps?.size ?: vm.apps.size} allowed apps · ${draft.catalogs?.size ?: vm.allCatalogs.size} allowed catalogs. Adjust these in Profiles after setup. Other apps retain their own parental controls.")
            Text("Next, configure the administrator PIN if needed and test the PIN used to return to a parent profile.")
            Chip("Continue") {draft=draft.copy(name=draft.name.trim().ifBlank {"Kids"});stage=if(vm.pin.pinSet)2 else 1}
        }
    }
}
