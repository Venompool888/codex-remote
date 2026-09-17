package app.codexremote.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.codexremote.android.RemoteImageRepository
import app.codexremote.android.presentation.artifacts.ArtifactsController
import app.codexremote.android.presentation.catalog.CatalogController
import app.codexremote.android.presentation.composer.ComposerController
import app.codexremote.android.presentation.connections.ConnectionsController
import app.codexremote.android.presentation.conversation.ConversationController
import app.codexremote.android.presentation.interactions.InteractionsController
import app.codexremote.android.presentation.projects.ProjectsController
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.artifacts.ArtifactsDialog
import app.codexremote.android.ui.artifacts.ImageViewerDialog
import app.codexremote.android.ui.catalog.RemoteCatalogDialog
import app.codexremote.android.ui.composer.ComposerSection
import app.codexremote.android.ui.connections.ConnectionsScreen
import app.codexremote.android.ui.conversation.ConversationScreen
import app.codexremote.android.ui.interactions.InteractionDialogCompose
import app.codexremote.android.ui.projects.RemoteProjectDialogCompose
import app.codexremote.android.ui.sidebar.SidebarDrawer
import app.codexremote.android.ui.theme.AppColors
import app.codexremote.android.ui.theme.CodexTheme

import app.codexremote.android.RemoteImageContent
import app.codexremote.android.ui.interactions.ApprovalDialog
import app.codexremote.android.ui.interactions.ApprovalUiState

val LocalRemoteImageRepository = staticCompositionLocalOf<RemoteImageRepository?> { null }
val LocalRemoteImagePresenter = staticCompositionLocalOf<(RemoteImageContent, String) -> Unit> { { _, _ -> } }
val LocalRemoteImageScope = staticCompositionLocalOf<String?> { null }
val LocalRemoteAttachmentOpener = staticCompositionLocalOf<(app.codexremote.android.MessageAttachment) -> Unit> { {} }

@Composable
fun CodexApp(
    connectionsController: ConnectionsController,
    projectsController: ProjectsController,
    sidebarController: SidebarController,
    conversationController: ConversationController,
    composerController: ComposerController,
    catalogController: CatalogController,
    interactionsController: InteractionsController,
    artifactsController: ArtifactsController,
    imageRepository: RemoteImageRepository? = null,
    imageScope: String? = null,
    onOpenImage: (RemoteImageContent, String) -> Unit = { _, _ -> },
    approvalState: ApprovalUiState? = null,
    onApprovalChoice: (key: String, choice: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    onOpenAttachment: (app.codexremote.android.MessageAttachment) -> Unit = {},
    addConnectionController: app.codexremote.android.presentation.connections.AddConnectionController? = null,
    onScanConnection: () -> Unit = {}
) {
    CompositionLocalProvider(
        LocalRemoteImageRepository provides imageRepository,
        LocalRemoteImageScope provides imageScope,
        LocalRemoteImagePresenter provides onOpenImage,
        LocalRemoteAttachmentOpener provides onOpenAttachment
    ) {
        CodexTheme {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(AppColors.background)
            ) {
                val connectionsState = connectionsController.uiState.value

                // Setup replaces the base surface so its empty space cannot activate controls underneath.
                if (addConnectionController?.uiState?.value?.isOpen == true) {
                    app.codexremote.android.ui.connections.AddConnectionScreen(addConnectionController, onScanConnection)
                } else {
                // 1. Base screen inside SidebarDrawer
                SidebarDrawer(
                    controller = sidebarController,
                    edgeGesturesEnabled = !connectionsState.isManagerOpen && connectionsState.connections.isNotEmpty()
                ) {
                    if (connectionsState.isManagerOpen || connectionsState.connections.isEmpty()) {
                        ConnectionsScreen(
                            controller = connectionsController,
                            onOpenAddProject = { serverUrl ->
                                connectionsController.requestAddProject(serverUrl.orEmpty())
                            },
                            onOpenAddConnection = { addConnectionController?.open() }
                        )
                        if (approvalState != null) {
                            val approvalKey = approvalState.key
                            ApprovalDialog(
                                state = approvalState,
                                onChoice = { choice -> onApprovalChoice(approvalKey, choice) },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .imePadding()
                                    .padding(horizontal = 12.dp, vertical = 12.dp)
                            )
                        }
                    } else {
                        ConversationScreen(
                            controller = conversationController,
                            composerSlot = {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                        .imePadding()
                                ) {
                                    if (approvalState != null) {
                                        val approvalKey = approvalState.key
                                        ApprovalDialog(
                                            state = approvalState,
                                            onChoice = { choice -> onApprovalChoice(approvalKey, choice) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                    ComposerSection(controller = composerController)
                                }
                            }
                        )
                    }
                }

                }

                // 2. Overlays & Dialogs (Real Compose nodes)
                RemoteProjectDialogCompose(controller = projectsController)

                RemoteCatalogDialog(controller = catalogController)

                InteractionDialogCompose(controller = interactionsController)

                ArtifactsDialog(controller = artifactsController)

                ImageViewerDialog(controller = artifactsController)
            }
        }
    }
}
