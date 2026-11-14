package app.ember.tv.player

import androidx.media3.common.util.UnstableApi

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerActivity.navigateSeek(target: Long) {
    playbackEngine?.navigate(target,SeekPreference.fast(config.seekMode))
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerActivity.changeAudioEffects(value: AudioEffects) {
    config=config.copy(audioEffects=value)
    prefs.playbackConfig=config
    audioEffectState.value=value
    if(playbackEngine?.compatibility?.audioEffects(value)==false)
        android.widget.Toast.makeText(this,"Audio effects are unavailable for this output",android.widget.Toast.LENGTH_SHORT).show()
    changed()
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerActivity.editPlayerShortcuts() {
    fun save(values: List<String>) {config=config.copy(quickShortcuts=PlayerShortcuts.normalized(values));prefs.playbackConfig=config;changed()}
    val current=PlayerShortcuts.normalized(config.quickShortcuts)
    val entries=current.map { id->PlayerShortcuts.choices.first {it.first==id} } + PlayerShortcuts.choices.filter {current.size<8 && it.first !in current}
    showPlayerActions("Player shortcuts",entries.map { (id,label) ->
        PlayerDialogAction(if(id in current) "${current.indexOf(id)+1}. $label" else "Add $label",selected=id in current) {
            val index=current.indexOf(id)
            if(index<0) {if(current.size<8)save(current+id);editPlayerShortcuts()}
            else showPlayerActions(label,buildList {
                if(index>0)add(PlayerDialogAction("Move earlier") {val list=current.toMutableList();java.util.Collections.swap(list,index,index-1);save(list);editPlayerShortcuts()})
                if(index<current.lastIndex)add(PlayerDialogAction("Move later") {val list=current.toMutableList();java.util.Collections.swap(list,index,index+1);save(list);editPlayerShortcuts()})
                add(PlayerDialogAction("Remove shortcut",destructive=true) {save(current-id);editPlayerShortcuts()})
            })
        }
    }+PlayerDialogAction("Reset shortcuts") {save(PlayerShortcuts.defaults);editPlayerShortcuts()},message="Choose up to eight controls. Select an existing shortcut to move or remove it.")
}
