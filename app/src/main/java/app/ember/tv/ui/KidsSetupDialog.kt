package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    // #468: the setup's steps across the top and the editors' footer, as in the other editors.
    MediaDialog(if(stage==3)"Kids profile ready" else "Set up a Kids profile",close,resizeForKeyboard=true,wide=true,scrollBody=false) {
        EditorSteps(listOf("choices" to "Choices","pin" to "Parent PIN","ready" to "Ready"),if(stage==3)"ready" else "choices","kids_step_",
            done={id->id=="choices" && stage==3 || id=="pin" && stage==3 && verified}) {id->if(id=="choices" && stage==3){stage=0;verified=false}}
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberMenuScrollState()),
            verticalArrangement=androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
            if(stage==3) {
                EditorHeading("Ready to save",stringResource(R.string.tx_administrator_pin_verified_has_local_playback, draft.name))
                Note(KidsContentPolicy.summary(draft))
                if(verifiedRevision!=vm.pin.revision)Chip("Verify administrator PIN again") {stage=2}
            } else {
                EditorHeading("Name","What the profile is called on the profile picker.")
                TextInput(draft.name,{draft=draft.copy(name=it.take(60))},"Profile name")
                EditorHeading("What kids can see","Content, apps and catalogs this profile allows.")
                KidsContentSettings(draft){change->draft=change(draft)}
                Note("${draft.apps?.size ?: vm.apps.size} allowed apps · ${draft.catalogs?.size ?: vm.allCatalogs.size} allowed catalogs. Adjust these in Profiles after setup. Other apps retain their own parental controls.")
            }
        }
        if(stage==3) EditorFooter("Review choices",{stage=0;verified=false},null) {
            PrimaryAction("Save Kids profile",verified && verifiedRevision==vm.pin.revision,androidx.compose.ui.Modifier.testTag("kids_save")) {
                if(verified && verifiedRevision==vm.pin.revision && vm.profiles.completeKidsSetup(draft)){onReady(draft.id);close()}
            }
        } else EditorFooter("Cancel",close,stringResource(R.string.tx_next_configure_the_administrator_pin_if),warn=false) {
            PrimaryAction("Continue",modifier=androidx.compose.ui.Modifier.testTag("kids_continue")) {draft=draft.copy(name=draft.name.trim().ifBlank {"Kids"});stage=if(vm.pin.pinSet)2 else 1}
        }
    }
}
