package app.codexremote.android.ui.sidebar

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.material3.ripple
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.R
import app.codexremote.android.RemoteConnection
import app.codexremote.android.RemoteProject
import app.codexremote.android.RemoteThread
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.presentation.sidebar.SidebarUiState
import app.codexremote.android.ui.theme.AppColors

@Composable
private fun Modifier.sidebarEdgeOpenGesture(
    onOpen: () -> Unit
): Modifier {
    val currentOnOpen by rememberUpdatedState(onOpen)
    return this.pointerInput(Unit) {
        val triggerDistancePx = 40.dp.toPx()
        val touchSlop = viewConfiguration.touchSlop

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!down.changedToDownIgnoreConsumed()) {
                return@awaitEachGesture
            }

            val pointerId = down.id
            var totalX = 0f
            var totalY = 0f
            var horizontalSlopPassed = false
            var triggered = false

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (change.isConsumed && !horizontalSlopPassed) {
                    break
                }
                if (change.changedToUp() || !change.pressed) {
                    break
                }

                val positionChange = change.position - change.previousPosition
                totalX += positionChange.x
                totalY += positionChange.y

                val absX = abs(totalX)
                val absY = abs(totalY)

                if (!horizontalSlopPassed) {
                    if (absY >= touchSlop && absY > absX) {
                        // Predominantly vertical drag: leave to underlying LazyColumn / scrolling
                        break
                    }
                    if (totalX <= -touchSlop) {
                        // Leftward drag on open edge detector: leave to system back / unconsumed
                        break
                    }

                    if (totalX >= touchSlop && totalX > absY) {
                        horizontalSlopPassed = true
                        change.consume()
                    }
                } else {
                    change.consume()
                }

                if (horizontalSlopPassed && !triggered) {
                    if (totalX >= triggerDistancePx) {
                        triggered = true
                        currentOnOpen()
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.sidebarCloseGesture(
    onClose: () -> Unit
): Modifier {
    val currentOnClose by rememberUpdatedState(onClose)
    return this.pointerInput(Unit) {
        val triggerDistancePx = 40.dp.toPx()
        val touchSlop = viewConfiguration.touchSlop

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!down.changedToDownIgnoreConsumed()) {
                do {
                    val event = awaitPointerEvent()
                } while (event.changes.any { it.pressed })
                return@awaitEachGesture
            }

            val pointerId = down.id
            var totalX = 0f
            var totalY = 0f
            var horizontalSlopPassed = false
            var triggered = false

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (change.isConsumed && !horizontalSlopPassed) {
                    break
                }
                if (change.changedToUp() || !change.pressed) {
                    break
                }

                val positionChange = change.position - change.previousPosition
                totalX += positionChange.x
                totalY += positionChange.y

                val absX = abs(totalX)
                val absY = abs(totalY)

                if (!horizontalSlopPassed) {
                    if (absY >= touchSlop && absY > absX) {
                        // Predominantly vertical drag: leave to underlying LazyColumn / scrolling
                        break
                    }
                    if (totalX >= touchSlop) {
                        // Rightward drag on drawer: ignore
                        break
                    }

                    if (-totalX >= touchSlop && absX > absY) {
                        horizontalSlopPassed = true
                        change.consume()
                    }
                } else {
                    change.consume()
                }

                if (horizontalSlopPassed && !triggered) {
                    if (totalX <= -triggerDistancePx) {
                        triggered = true
                        currentOnClose()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
fun SidebarDrawer(
    controller: SidebarController,
    modifier: Modifier = Modifier,
    edgeGesturesEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val state = controller.uiState.value
    val currentController by rememberUpdatedState(controller)

    val density = LocalDensity.current
    val edgeWidth = with(density) { 28.dp.toPx() }
    val exclusionHeight = with(density) { 200.dp.toPx() }
    val edgeOpenModifier = if (!state.isOpen && edgeGesturesEnabled) {
        Modifier
            .systemGestureExclusion { coordinates ->
                // Reserve only a central one-handed opening zone; keep system
                // back gestures available along the rest of the screen edge.
                val height = coordinates.size.height.toFloat()
                val top = ((height - exclusionHeight) / 2f).coerceAtLeast(0f)
                Rect(0f, top, edgeWidth, (top + exclusionHeight).coerceAtMost(height))
            }
            .sidebarEdgeOpenGesture { currentController.openSidebar() }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(edgeOpenModifier)
    ) {
        content()

        AnimatedVisibility(
            visible = state.isOpen,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.drawerScrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { currentController.closeSidebar() }
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.82f)
                        .animateEnterExit(
                            enter = slideInHorizontally(initialOffsetX = { -it }),
                            exit = slideOutHorizontally(targetOffsetX = { -it })
                        )
                        .sidebarCloseGesture {
                            currentController.closeSidebar()
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* consume clicks */ },
                    color = AppColors.surface,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(top = 10.dp)
                    ) {
                        if (state.showProjectPage) {
                            SidebarProjectPage(controller = controller)
                        } else {
                            // Fixed top New chat row with search toggle action
                            SidebarHeader(controller = controller)

                            // Search input if open
                            if (state.searchVisible) {
                                OutlinedTextField(
                                    value = state.searchQuery,
                                    onValueChange = { controller.updateSearchQuery(it) },
                                    placeholder = { Text("Search chats…", fontSize = 14.sp) },
                                    singleLine = true,
                                    trailingIcon = {
                                        if (state.searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { controller.updateSearchQuery("") }) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_close),
                                                    contentDescription = "Clear search",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 4.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AppColors.primary,
                                        unfocusedBorderColor = AppColors.outlineVariant,
                                        focusedTextColor = AppColors.onSurface,
                                        unfocusedTextColor = AppColors.onSurface
                                    )
                                )
                            }

                            // Shared horizontal connection selector
                            SidebarConnectionSelector(controller = controller)

                            Spacer(modifier = Modifier.height(4.dp))

                            // Main scrolling drawer content
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                SidebarContent(controller = controller, state = state)
                            }

                            // Fixed connection footer at the bottom
                            SidebarFooter(controller = controller)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarHeader(
    controller: SidebarController,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { controller.createNewThread() }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_new_chat),
                contentDescription = "New chat",
                tint = AppColors.onSurface,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "New chat",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.onSurface
            )
        }

        IconButton(
            onClick = { controller.toggleSearch() },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = "Search chats",
                tint = if (state.searchVisible) AppColors.primary else AppColors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
internal fun SidebarConnectionSelector(
    controller: SidebarController,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value
    val connections = state.connections
    if (connections.isEmpty()) return

    val scrollState = rememberScrollState()

    // Narrowly scoped gesture boundary around the horizontal selector:
    // Prevents leftward/horizontal swipes used to scroll or overscroll the connection chips
    // from bubbling unconsumed deltas up to the outer Surface's sidebarCloseGesture and closing the drawer.
    // Ordinary taps and vertical gestures remain unswallowed.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    var totalX = 0f
                    var totalY = 0f
                    val touchSlop = viewConfiguration.touchSlop
                    var isHorizontal = false

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (change.changedToUp() || !change.pressed) break

                        val delta = change.position - change.previousPosition
                        totalX += delta.x
                        totalY += delta.y

                        if (!isHorizontal) {
                            if (abs(totalY) > touchSlop && abs(totalY) > abs(totalX)) {
                                // Predominantly vertical drag: leave to LazyColumn or other parents
                                break
                            }
                            if (abs(totalX) > touchSlop && abs(totalX) > abs(totalY)) {
                                isHorizontal = true
                            }
                        }

                        if (isHorizontal) {
                            // Consume horizontal drag delta so parent sidebarCloseGesture doesn't trigger
                            change.consume()
                        }
                    }
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            connections.forEach { connection ->
                ConnectionChip(
                    connection = connection,
                    isSelected = state.connectedServerUrl != null &&
                        (state.connectedServerUrl == connection.serverUrl ||
                         state.connectedServerUrl.trim().trimEnd('/') == connection.serverUrl.trim().trimEnd('/')),
                    isConnected = connection.serverUrl in state.connectedServerUrls ||
                        state.connectedServerUrls.any { it.trim().trimEnd('/') == connection.serverUrl.trim().trimEnd('/') },
                    isConnecting = connection.serverUrl in state.connectingServers ||
                        state.connectingServers.any { it.trim().trimEnd('/') == connection.serverUrl.trim().trimEnd('/') },
                    onClick = { controller.selectConnection(connection.serverUrl) }
                )
            }
        }
    }
}

@Composable
private fun ConnectionChip(
    connection: RemoteConnection,
    isSelected: Boolean,
    isConnected: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleLabel = connection.name.ifBlank { hostLabel(connection.serverUrl) }
    val accessibleLabel = if (connection.name.isNotBlank()) {
        "${connection.name} (${connection.serverUrl})"
    } else {
        connection.serverUrl
    }
    val statusDescription = when {
        isConnected -> "Connected"
        isConnecting -> "Connecting"
        else -> "Offline"
    }
    val statusColor = when {
        isConnected -> AppColors.success
        isConnecting -> AppColors.warning
        else -> AppColors.onSurfaceMuted
    }
    val chipBackground = if (isSelected) AppColors.surfaceContainerHigh else AppColors.surface
    val borderColor = if (isSelected) AppColors.onSurface else AppColors.outlineVariant
    val borderWidth = if (isSelected) 1.5.dp else 1.dp
    val contentColor = if (isSelected) AppColors.onSurface else AppColors.onSurfaceVariant
    val textWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = isSelected
                contentDescription = "$accessibleLabel, $statusDescription"
                stateDescription = statusDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .height(29.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(chipBackground, RoundedCornerShape(8.dp))
                .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
                .indication(interactionSource, ripple())
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(statusColor, CircleShape)
            )
            // Compact host/computer icon
            Icon(
                painter = painterResource(R.drawable.ic_computer),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(13.dp)
            )
            // Visible connection label (ellipsized within bounded width)
            Text(
                text = visibleLabel,
                fontSize = 12.sp,
                fontWeight = textWeight,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp)
            )
        }
    }
}

@Composable
private fun SidebarContent(
    controller: SidebarController,
    state: SidebarUiState,
    modifier: Modifier = Modifier
) {
    val grouping = remember(
        state.threads,
        state.projects,
        state.trashedProjects,
        state.connectedServerUrl,
        state.scopedWorkspace,
        state.searchQuery
    ) {
        groupThreads(
            threads = state.threads,
            projects = state.projects,
            connectedServerUrl = state.connectedServerUrl,
            scopedWorkspace = state.scopedWorkspace,
            searchQuery = state.searchQuery,
            trashedProjects = state.trashedProjects
        )
    }

    val isSearching = state.searchQuery.isNotBlank()
    val query = state.searchQuery.trim().lowercase()

    val projectsToDisplay = remember(state.projects, grouping.projectThreads, isSearching, query) {
        if (!isSearching) {
            state.projects
        } else {
            state.projects.filter { proj ->
                proj.name.lowercase().contains(query) ||
                proj.workspace.lowercase().contains(query) ||
                proj.serverUrl.lowercase().contains(query) ||
                (grouping.projectThreads[proj.id]?.isNotEmpty() == true)
            }
        }
    }

    val ungroupedAll = grouping.ungroupedThreads
    val ungroupedToShow = if (isSearching || state.showAllUngroupedThreads) ungroupedAll else ungroupedAll.take(5)
    val hasMoreUngrouped = !isSearching && !state.showAllUngroupedThreads && ungroupedAll.size > 5

    val hasMatchingResults = !isSearching || ungroupedAll.isNotEmpty() || projectsToDisplay.any { proj ->
        grouping.projectThreads[proj.id]?.isNotEmpty() == true ||
        proj.name.lowercase().contains(query) ||
        proj.workspace.lowercase().contains(query)
    }

    val hasContent = ungroupedAll.isNotEmpty() || state.projects.isNotEmpty() || state.trashedProjects.isNotEmpty()

    if (!isSearching) {
        if (state.isLoadingThreads && !hasContent) {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            return
        }

        if (!hasContent && !state.hasMore) {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No recent chats",
                    fontSize = 14.sp,
                    color = AppColors.onSurfaceMuted
                )
            }
            return
        }
    }

    val hasScope = !state.scopedWorkspace.isNullOrBlank()

    // Dialog for recycle bin
    if (state.showRecycleBin) {
        RecycleBinDialog(
            trashedProjects = state.trashedProjects,
            onRestore = { controller.restoreProject(it) },
            onDismiss = { controller.closeRecycleBin() }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
    ) {
        // Scoped workspace back banner only for the no-project legacy scoped fallback
        if (state.scopedWorkspace != null && state.projects.isEmpty()) {
            item(key = "back_scoped_banner") {
                TextButton(
                    onClick = { controller.back() },
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text("Back to all chats", color = AppColors.primary, fontSize = 13.sp)
                }
            }
        }

        // When search has no matching chats or projects, render a concise no-match item while leaving Recycle bin reachable
        if (isSearching && !hasMatchingResults) {
            item(key = "no_search_matches") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No matching chats",
                        fontSize = 14.sp,
                        color = AppColors.onSurfaceMuted
                    )
                }
            }
        }

        // Projectless / ungrouped recent tasks near the top
        if (ungroupedToShow.isNotEmpty()) {
            items(ungroupedToShow, key = { it.id }) { thread ->
                SidebarThreadRow(
                    thread = thread,
                    isSelected = thread.id == state.activeThreadId,
                    isIndented = false,
                    onClick = { controller.selectThread(thread) }
                )
            }

            if (hasMoreUngrouped) {
                item(key = "ungrouped_show_more") {
                    ShowMoreRow(
                        onClick = { controller.showMoreUngroupedThreads() },
                        isIndented = false
                    )
                }
            }

            item(key = "ungrouped_projects_spacing") {
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        // Muted Projects section heading with add-project button and optional recycle bin
        item(key = "projects_heading") {
            ProjectsHeading(
                onAddProject = { controller.openNewProject() },
                trashedCount = state.trashedProjects.size,
                onOpenRecycleBin = { controller.openRecycleBin() }
            )
        }

        // Projects list with inline expandable child tasks
        projectsToDisplay.forEach { project ->
            val isAutoExpandedForSearch = isSearching && (grouping.projectThreads[project.id]?.isNotEmpty() == true)
            val isExpanded = project.id in state.expandedProjectIds || isAutoExpandedForSearch
            val hasRunningChildren = grouping.projectThreads[project.id].orEmpty().any(RemoteThread::isRunning)

            val isActive = if (hasScope) {
                val scopeWs = normalizePath(state.scopedWorkspace!!)
                val scopeServer = state.scopedServer?.trim()?.trimEnd('/')
                normalizePath(project.workspace) == scopeWs &&
                    (scopeServer == null || project.serverUrl.trim().trimEnd('/') == scopeServer)
            } else {
                project.id == state.activeProjectId
            }

            item(key = "project_${project.id}") {
                SidebarProjectRow(
                    project = project,
                    isActive = isActive,
                    isExpanded = isExpanded,
                    hasRunningChildren = hasRunningChildren,
                    onClick = {
                        val currentlyExpanded = project.id in state.expandedProjectIds
                        if (currentlyExpanded) {
                            controller.toggleProjectExpanded(project.id)
                        } else {
                            controller.toggleProjectExpanded(project.id)
                            controller.selectProject(project)
                        }
                    },
                    onNewChat = { controller.newThreadInProject(project) },
                    onTrash = { controller.trashProject(it) }
                )
            }

            if (isExpanded) {
                val projectThreads = grouping.projectThreads[project.id].orEmpty()
                val showAllForThisProject = isSearching || (project.id in state.expandedChildrenProjectIds)
                val childrenToShow = if (showAllForThisProject) projectThreads else projectThreads.take(5)
                val hasShowMore = !showAllForThisProject && projectThreads.size > 5

                items(childrenToShow, key = { "thread_${it.id}" }) { childThread ->
                    SidebarThreadRow(
                        thread = childThread,
                        isSelected = childThread.id == state.activeThreadId,
                        isIndented = true,
                        onClick = { controller.selectThread(childThread) }
                    )
                }

                if (hasShowMore) {
                    item(key = "more_${project.id}") {
                        ShowMoreRow(
                            onClick = { controller.showMoreProjectChildren(project.id) },
                            isIndented = true
                        )
                    }
                }
            }
        }

        // Paging row if more chats exist
        if (state.hasMore) {
            item(key = "paging_footer") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isLoadingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(
                            onClick = { controller.loadMore() },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Load more chats", fontSize = 13.sp, color = AppColors.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectsHeading(
    onAddProject: () -> Unit,
    trashedCount: Int = 0,
    onOpenRecycleBin: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Projects",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.onSurfaceMuted,
            modifier = Modifier.weight(1f)
        )
        if (trashedCount > 0) {
            TextButton(
                onClick = onOpenRecycleBin,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Recycle bin",
                    fontSize = 12.sp,
                    color = AppColors.onSurfaceMuted
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        IconButton(
            onClick = onAddProject,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_folder_plus),
                contentDescription = "New project",
                tint = AppColors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarProjectRow(
    project: RemoteProject,
    isActive: Boolean,
    isExpanded: Boolean,
    hasRunningChildren: Boolean,
    onClick: () -> Unit,
    onNewChat: () -> Unit = {},
    onTrash: (RemoteProject) -> Unit,
    modifier: Modifier = Modifier
) {
    val rowShape = RoundedCornerShape(8.dp)
    val bg = if (isActive) AppColors.surfaceContainerHigh else Color.Transparent
    var menuExpanded by remember(project.id) { mutableStateOf(false) }
    var showDetails by remember(project.id) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(rowShape)
            .background(bg, rowShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true },
                onLongClickLabel = "Project options for ${project.name}"
            )
            .semantics {
                contentDescription = "Project ${project.name}"
            }
            .padding(horizontal = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                tint = if (isActive) AppColors.primary else AppColors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = project.name,
                fontSize = 14.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                color = AppColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onNewChat,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = "New chat in ${project.name}"
                    }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_new_chat),
                    contentDescription = null,
                    tint = AppColors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (!isExpanded && hasRunningChildren) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(16.dp)
                        .semantics { contentDescription = "AI is working" },
                    color = AppColors.running,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(
                painter = painterResource(
                    if (isExpanded) R.drawable.ic_codex_chevron_down else R.drawable.ic_chevron_right
                ),
                contentDescription = if (isExpanded) "Collapse ${project.name}" else "Expand ${project.name}",
                tint = AppColors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.background(AppColors.surfaceContainerHigh)
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Details",
                        color = AppColors.onSurface,
                        fontSize = 14.sp
                    )
                },
                onClick = {
                    menuExpanded = false
                    showDetails = true
                },
                modifier = Modifier.semantics {
                    contentDescription = "Details for ${project.name}"
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Move to recycle bin",
                        color = AppColors.error,
                        fontSize = 14.sp
                    )
                },
                onClick = {
                    menuExpanded = false
                    onTrash(project)
                },
                modifier = Modifier.semantics {
                    contentDescription = "Move ${project.name} to recycle bin"
                }
            )
        }
    }

    if (showDetails) {
        ProjectDetailsDialog(
            project = project,
            onDismiss = { showDetails = false }
        )
    }
}

@Composable
private fun ProjectDetailsDialog(
    project: RemoteProject,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember(project.id, project.workspace) { mutableStateOf(false) }

    val hasWorkspace = project.workspace.isNotBlank()
    val connectionDisplay = project.connectionName.ifBlank { hostLabel(project.serverUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Project details",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.onSurface
            )
        },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    ProjectDetailItem(
                        label = "Project name",
                        value = project.name.ifBlank { "(unnamed)" },
                        isMuted = project.name.isBlank()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ProjectDetailItem(
                        label = "Connection",
                        value = connectionDisplay.ifBlank { "(none)" },
                        isMuted = connectionDisplay.isBlank()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ProjectDetailItem(
                        label = "Server URL",
                        value = project.serverUrl.ifBlank { "(none)" },
                        isMuted = project.serverUrl.isBlank()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ProjectDetailItem(
                        label = "Workspace path",
                        value = project.workspace.ifBlank { "(blank)" },
                        isMuted = !hasWorkspace
                    )
                    if (copied) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Path copied to clipboard",
                            fontSize = 12.sp,
                            color = AppColors.success
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AppColors.primary)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (hasWorkspace) {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.setPrimaryClip(
                            android.content.ClipData.newPlainText("Workspace path", project.workspace)
                        )
                        copied = true
                    }
                },
                enabled = hasWorkspace
            ) {
                Icon(
                    painter = painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy),
                    contentDescription = null,
                    tint = if (!hasWorkspace) {
                        AppColors.onSurfaceMuted
                    } else if (copied) {
                        AppColors.success
                    } else {
                        AppColors.primary
                    },
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (copied) "Path copied" else "Copy path",
                    color = if (!hasWorkspace) {
                        AppColors.onSurfaceMuted
                    } else if (copied) {
                        AppColors.success
                    } else {
                        AppColors.primary
                    }
                )
            }
        },
        containerColor = AppColors.surfaceContainerHigh
    )
}

@Composable
private fun ProjectDetailItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isMuted: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.onSurfaceMuted
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            color = if (isMuted) AppColors.onSurfaceMuted else AppColors.onSurface,
            softWrap = true
        )
    }
}

@Composable
private fun RecycleBinDialog(
    trashedProjects: List<RemoteProject>,
    onRestore: (RemoteProject) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Recycle bin",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.onSurface
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Projects moved here can be restored. Files and chats remain on the host.",
                    fontSize = 12.sp,
                    color = AppColors.onSurfaceMuted,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                if (trashedProjects.isEmpty()) {
                    Text(
                        text = "Recycle bin is empty",
                        fontSize = 13.sp,
                        color = AppColors.onSurfaceMuted,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        items(trashedProjects, key = { it.id }) { proj ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_folder),
                                    contentDescription = null,
                                    tint = AppColors.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = proj.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppColors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val readablePath = readableWorkspacePath(proj.workspace, proj.name)
                                    val hostDisplay = proj.connectionName.ifBlank { hostLabel(proj.serverUrl) }
                                    Text(
                                        text = if (readablePath.isNotBlank()) "$readablePath · $hostDisplay" else hostDisplay,
                                        fontSize = 11.sp,
                                        color = AppColors.onSurfaceMuted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(
                                    onClick = { onRestore(proj) }
                                ) {
                                    Text("Restore", fontSize = 13.sp, color = AppColors.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AppColors.primary)
            }
        },
        containerColor = AppColors.surfaceContainerHigh
    )
}

internal fun readableWorkspacePath(workspace: String, projectName: String): String {
    if (workspace.isBlank()) return ""
    val trimmed = workspace.trim()

    if (trimmed.startsWith("remote-workspace://", ignoreCase = true) ||
        trimmed.startsWith("remote-workspace:", ignoreCase = true)
    ) {
        val raw = trimmed
            .removePrefix("remote-workspace://")
            .removePrefix("remote-workspace:")
            .removePrefix("REMOTE-WORKSPACE://")
            .removePrefix("REMOTE-WORKSPACE:")
            .trim()

        if (raw.startsWith('/')) {
            return if (raw.length > 1) raw.trimEnd('/') else raw
        }

        val firstSlash = raw.indexOf('/')
        if (firstSlash != -1 && firstSlash < raw.length - 1) {
            val candidatePath = raw.substring(firstSlash)
            return if (candidatePath.length > 1) candidatePath.trimEnd('/') else candidatePath
        }

        val clean = raw.trimEnd('/')
        val token = clean.substringAfterLast('/').ifBlank { clean }
        val stripped = token
            .removePrefix("session-")
            .removePrefix("workspace-")
            .removePrefix("ws-")
        val ref = when {
            stripped.length > 8 && stripped.contains('-') -> stripped.take(8)
            stripped.length > 8 -> stripped.takeLast(8)
            stripped.isNotBlank() -> stripped
            else -> Math.abs(workspace.hashCode()).toString(16).padStart(6, '0').take(6)
        }

        val fallback = projectName.ifBlank { "Workspace" }
        return if (ref.isNotBlank()) "$fallback ($ref)" else fallback
    }

    val path = trimmed.removePrefix("file://").removePrefix("FILE://")
    return if (path.length > 1) path.trimEnd('/') else path
}

internal fun hostLabel(serverUrl: String): String = serverUrl
    .substringAfter("://", serverUrl)
    .substringBefore('/')
    .substringBefore(':')
    .ifBlank { "Remote" }

@Composable
private fun SidebarThreadRow(
    thread: RemoteThread,
    isSelected: Boolean,
    isIndented: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rowShape = RoundedCornerShape(8.dp)
    val bg = if (isSelected) AppColors.surfaceContainerHigh else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (isIndented) 30.dp else 4.dp,
                end = 4.dp,
                top = 2.dp,
                bottom = 2.dp
            )
            .clip(rowShape)
            .background(bg, rowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = thread.title.ifBlank { "Untitled chat" },
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = AppColors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (thread.isRunning) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier
                    .size(16.dp)
                    .semantics { contentDescription = "AI is working" },
                color = AppColors.running,
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
private fun ShowMoreRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isIndented: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (isIndented) 30.dp else 4.dp,
                end = 4.dp,
                top = 2.dp,
                bottom = 2.dp
            )
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Show more",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.onSurfaceMuted
        )
    }
}

@Composable
private fun SidebarFooter(
    controller: SidebarController,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.surfaceContainerLow)
            .clickable { controller.openConnections() }
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_computer),
            contentDescription = null,
            tint = if (state.connectedServerUrl != null) AppColors.success else AppColors.warning,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.connectionLabel.ifBlank { "Connections" },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!state.connectedServerUrl.isNullOrBlank()) {
                Text(
                    text = state.connectedServerUrl,
                    fontSize = 11.sp,
                    color = AppColors.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = "Manage connections",
            tint = AppColors.onSurfaceMuted,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun SidebarProjectPage(
    controller: SidebarController,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value

    Column(modifier = modifier.fillMaxSize()) {
        // Header with Back button, Title, Search and Add
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { controller.back() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "Back to chats",
                    tint = AppColors.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Projects",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { controller.toggleSearch() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = "Search projects",
                    tint = if (state.searchVisible) AppColors.primary else AppColors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { controller.openNewProject() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder_plus),
                    contentDescription = "New project",
                    tint = AppColors.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Search input if open
        if (state.searchVisible) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { controller.updateSearchQuery(it) },
                placeholder = { Text("Search projects…", fontSize = 14.sp) },
                singleLine = true,
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { controller.updateSearchQuery("") }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Clear search",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.primary,
                    unfocusedBorderColor = AppColors.outlineVariant,
                    focusedTextColor = AppColors.onSurface,
                    unfocusedTextColor = AppColors.onSurface
                )
            )
        }

        // Shared horizontal connection selector
        SidebarConnectionSelector(controller = controller)

        Spacer(modifier = Modifier.height(4.dp))

        // Projects list
        if (state.filteredProjects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.searchQuery.isNotBlank()) "No matching projects" else "No projects found",
                    fontSize = 14.sp,
                    color = AppColors.onSurfaceMuted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.filteredProjects, key = { it.id }) { proj ->
                    val isSelected = proj.id == state.activeProjectId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) AppColors.surfaceContainerHigh else AppColors.surfaceContainerLow)
                            .clickable { controller.selectProject(proj) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = null,
                            tint = if (isSelected) AppColors.primary else AppColors.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = proj.name,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                color = AppColors.onSurface
                            )
                            Text(
                                text = proj.workspace,
                                fontSize = 12.sp,
                                color = AppColors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = proj.serverUrl,
                                fontSize = 11.sp,
                                color = AppColors.onSurfaceMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = "Active",
                                tint = AppColors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun groupThreads(
    threads: List<RemoteThread>,
    projects: List<RemoteProject>,
    connectedServerUrl: String?,
    scopedWorkspace: String?,
    searchQuery: String,
    trashedProjects: List<RemoteProject> = emptyList()
): ThreadGrouping {
    val query = searchQuery.trim().lowercase()

    // 1. Scoped workspace filtering only applied for the no-project legacy fallback
    val baseThreads = if (projects.isEmpty() && scopedWorkspace != null) {
        val normScope = normalizePath(scopedWorkspace)
        threads.filter { normalizePath(it.cwd) == normScope }
    } else {
        threads
    }

    // 2. Filter by search query if set
    val searchFilteredThreads = if (query.isBlank()) {
        baseThreads
    } else {
        baseThreads.filter {
            it.title.lowercase().contains(query) ||
            it.cwd.lowercase().contains(query) ||
            it.cwdName.lowercase().contains(query)
        }
    }

    val normConnectedServer = connectedServerUrl?.trim()?.trimEnd('/')

    // Projects belonging to the current connection (if known)
    val currentHostProjects = if (normConnectedServer != null) {
        projects.filter { it.serverUrl.trim().trimEnd('/') == normConnectedServer }
    } else {
        projects
    }

    // Trashed projects belonging to the current connection (if known)
    val currentHostTrashedProjects = if (normConnectedServer != null) {
        trashedProjects.filter { it.serverUrl.trim().trimEnd('/') == normConnectedServer }
    } else {
        trashedProjects
    }

    val projectThreadsMap = mutableMapOf<String, MutableList<RemoteThread>>()
    projects.forEach { projectThreadsMap[it.id] = mutableListOf() }

    val ungroupedThreads = mutableListOf<RemoteThread>()

    searchFilteredThreads.forEach { thread ->
        if (thread.cwd.isBlank()) {
            ungroupedThreads.add(thread)
        } else {
            val normCwd = normalizePath(thread.cwd)
            // 1. Visible project wins
            val matchingProject = currentHostProjects.firstOrNull {
                normalizePath(it.workspace) == normCwd
            }
            if (matchingProject != null) {
                projectThreadsMap[matchingProject.id]?.add(thread)
            } else {
                // 2. Check if it matches a trashed project on the current host
                val isTrashedProjectThread = currentHostTrashedProjects.any {
                    normalizePath(it.workspace) == normCwd
                }
                // If it matches a trashed project, do NOT add to ungroupedThreads
                if (!isTrashedProjectThread) {
                    ungroupedThreads.add(thread)
                }
            }
        }
    }

    return ThreadGrouping(
        ungroupedThreads = ungroupedThreads,
        projectThreads = projectThreadsMap
    )
}

internal data class ThreadGrouping(
    val ungroupedThreads: List<RemoteThread>,
    val projectThreads: Map<String, List<RemoteThread>>
)

internal fun normalizePath(path: String): String =
    path.trim().let { if (it == "/") it else it.trimEnd('/') }
