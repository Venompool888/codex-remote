@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.catalog.*
import app.codexremote.android.ui.catalog.RemoteCatalogDialog
class CapabilityStatusLayoutDeviceTest : InstrumentationTestCase() {
    fun testBlockingStatesRemainReadableAtLargeFont() {
        for((state,label) in listOf("authorization_required" to "Requires authorization","missing_dependency" to "Missing dependency","disabled" to "Disabled")) {
            var selected=false
            val controller=CatalogController(onFetchCatalog={_,done->done(listOf(CatalogCapabilityItem("id","Remote capability","Description","plugin",state,"Manage on host")),emptyList())},onFetchApps={it(emptyList(),null)},onSelectCapability={selected=true})
            instrumentation.runOnMainSync {controller.openCatalog("https://fixture.invalid","/fixture")}
            val activity=instrumentation.composeFixture {RemoteCatalogDialog(controller)}
            try {instrumentation.awaitUiText(label);instrumentation.clickUi("Remote capability");instrumentation.awaitUiText("Manage on host");assertFalse(selected)}
            finally {instrumentation.runOnMainSync {activity.finish()}}
        }
    }
}
