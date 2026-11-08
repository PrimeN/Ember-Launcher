package app.ember.tv

import app.ember.tv.data.*

internal fun HomeViewModel.lightAppItems(showHidden:Boolean=false):List<AppEntry> = LightNavigation.ordered(
    if(showHidden)appAccess.page(true) else homeApps,visibleTabs,personalHomes.current.lightOrder,
    personalHomes.current.lightNavHidden,showHidden)

internal fun HomeViewModel.canManageHomeItem(id:String):Boolean = appAccess.canLaunch(id) ||
    (look.uiStyle==InterfaceStyles.LIGHT && LightNavigation.destination(id)?.let {it in visibleTabs}==true)

internal fun HomeViewModel.setLightAppOrder(order:List<String>) {
    val permitted=lightAppItems(true).map {it.pkg}
    val merged=AppOrder.merge(permitted,order)
    personalHomes.update {it.copy(lightOrder=merged)}
}

internal fun HomeViewModel.toggleLightNavigation(id:String) {
    if(LightNavigation.destination(id)?.let {it in visibleTabs}!=true)return
    personalHomes.update {home->home.copy(lightNavHidden=if(id in home.lightNavHidden)home.lightNavHidden-id else home.lightNavHidden+id)}
}
