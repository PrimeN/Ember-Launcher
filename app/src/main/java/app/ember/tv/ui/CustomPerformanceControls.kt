package app.ember.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.ember.tv.HomeViewModel
import app.ember.tv.data.CustomPerformance

@Composable internal fun CustomPerformanceControls(vm:HomeViewModel) {
    val c=vm.look.customPerformance
    ToggleRow("Smart hero framing", "Analyse a tiny artwork sample to choose the crop. Off keeps safe standard framing.",c.heroAnalysis) {vm.look.updateCustomPerformance {it.copy(heroAnalysis=!it.heroAnalysis)}}
    ToggleRow("Artwork colour analysis", "Match focused app, media and collection backgrounds to their colours. Off keeps the theme colour.",c.colorAnalysis) {vm.look.updateCustomPerformance {it.copy(colorAnalysis=!it.colorAnalysis)}}
    ToggleRow("Interface motion", "Page, panel, image fades and animated focus effects. Static focus outlines remain.",c.animations) {vm.look.updateCustomPerformance {it.copy(animations=!it.animations)}}
    ToggleRow("Progressive hero images", "Show a smaller preview while sharp artwork loads. Off requests the final image directly.",c.progressiveArtwork) {vm.look.updateCustomPerformance {it.copy(progressiveArtwork=!it.progressiveArtwork)}}
    ToggleRow("Collection motion", "Allow opted-in GIF and muted video previews. Off uses still covers.",c.collectionMotion) {vm.look.updateCustomPerformance {it.copy(collectionMotion=!it.collectionMotion)}}
    ButtonRow(spacing=10.dp) {
        (0..2).forEach {n->Chip(if(n==0)"Prefetch off" else "Prefetch $n",selected=c.prefetch==n) {vm.look.updateCustomPerformance {it.copy(prefetch=n)}}}
        Chip("Reset custom controls") {vm.look.updateCustomPerformance {CustomPerformance()}}
    }
}
