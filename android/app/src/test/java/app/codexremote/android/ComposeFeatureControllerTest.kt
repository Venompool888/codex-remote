package app.codexremote.android

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.codexremote.android.presentation.catalog.*
import app.codexremote.android.presentation.composer.*
import app.codexremote.android.presentation.connections.*
import app.codexremote.android.presentation.sidebar.*
import org.junit.Assert.*
import org.junit.Test

class ComposeFeatureControllerTest {
    @Test fun oldCatalogRefreshCannotOverwriteNewerResult() {
        val callbacks=mutableListOf<(List<CatalogCapabilityItem>,List<String>)->Unit>()
        val controller=CatalogController(onFetchCatalog={_,cb->callbacks+=cb},onFetchApps={it(emptyList(),null)})
        controller.openCatalog("host","/a");controller.refresh()
        callbacks[1](listOf(CatalogCapabilityItem("new","New","")),emptyList())
        callbacks[0](listOf(CatalogCapabilityItem("old","Old","")),emptyList())
        assertEquals("new",controller.uiState.value.capabilities.single().id)
    }
    @Test fun dismissedCatalogDoesNotAcceptOldApps() {
        var callback:((List<ConnectedAppItem>,String?)->Unit)?=null
        val controller=CatalogController(onFetchApps={callback=it})
        controller.openCatalog("host","/a");controller.closeCatalog();callback!!(listOf(ConnectedAppItem("old")),null)
        assertTrue(controller.uiState.value.apps.isEmpty());assertFalse(controller.uiState.value.isOpen)
    }
    @Test fun disabledCapabilityExplainsAndDoesNotAttach() {
        var selected=0;val controller=CatalogController(onSelectCapability={selected++})
        controller.selectCapability(CatalogCapabilityItem("blocked","Blocked","",state="authorization_required"))
        assertNotNull(controller.uiState.value.explanationDialogItem);assertEquals(0,selected)
    }
    @Test fun composerPreservesReplacementDraftAfterOldDispatch() {
        var callback:((Boolean)->Unit)?=null
        val controller=ComposerController(onSend={_,_,cb->callback=cb})
        controller.setDraftIdentity("a");controller.updateText("first");controller.sendOrStop()
        controller.setDraftIdentity("b");controller.updateTextFieldValue(TextFieldValue("second",TextRange(2),TextRange(0,2)))
        callback!!(true)
        assertEquals("second",controller.uiState.value.text);assertEquals(TextRange(0,2),controller.uiState.value.textFieldValue.composition)
    }
    @Test fun permissionOptionsRespectHostFlagsAndConfirmation() {
        var selected="";val controller=ComposerController(onPermissionModeChanged={selected=it})
        controller.setPermissionOptions(listOf(ComposerOption("workspace","Ask"),ComposerOption("disabled-full","Blocked",enabled=false),ComposerOption("danger-full-access","Full")),"workspace")
        controller.selectPermission("disabled-full");assertEquals("",selected)
        controller.selectPermission("danger-full-access");assertEquals("",selected);assertTrue(controller.uiState.value.showFullAccessWarning)
        controller.confirmFullAccess();assertEquals("danger-full-access",selected)
    }
    @Test fun taskSwitchDismissesPendingPermissionChoice() {
        var selected = ""
        val controller = ComposerController(onPermissionModeChanged = { selected = it })
        controller.setDraftIdentity("old")
        controller.setPermissionOptions(listOf(ComposerOption("danger-full-access", "Full")), null)
        controller.selectPermission("danger-full-access")
        assertTrue(controller.uiState.value.showFullAccessWarning)
        controller.setDraftIdentity("new")
        controller.confirmFullAccess()
        assertEquals("", selected)
        assertFalse(controller.uiState.value.showFullAccessWarning)
    }
    @Test fun connectionBatchDeleteRequiresConfirmationAndPrunesStaleSelection() {
        var deleted=emptySet<String>();val controller=ConnectionsController(onDeleteConfirmed={deleted=it})
        val a=RemoteProject("a","A","Host A","https://a","/a");val b=RemoteProject("b","B","Host B","https://b","/b")
        controller.updateConnections(listOf(a,b),"a","https://a",emptySet(),emptySet())
        controller.startSelection(a.serverUrl);controller.selectAll();controller.deleteSelected()
        assertTrue(deleted.isEmpty());controller.dismissDelete();assertTrue(deleted.isEmpty())
        controller.updateConnections(listOf(b),"b","https://b",emptySet(),emptySet());assertEquals(setOf(b.serverUrl),controller.uiState.value.selectedServers)
        controller.deleteSelected();controller.confirmDelete();assertEquals(setOf(b.serverUrl),deleted);assertFalse(controller.uiState.value.selectionMode)
    }
    @Test fun scopedSearchNeverIncludesAnotherWorkspace() {
        val controller=SidebarController()
        controller.setThreads(listOf(RemoteThread("a","same","/a","idle",0,false),RemoteThread("b","same","/b","idle",0,false)))
        controller.setProjectScope("host","/a");controller.updateSearchQuery("same")
        assertEquals(listOf("a"),controller.uiState.value.filteredThreads.map {it.id})
        controller.back();assertEquals(2,controller.uiState.value.filteredThreads.size)
    }
}
