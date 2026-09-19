package app.codexremote.android.ui.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codexremote.android.R
import app.codexremote.android.SubagentDirectoryEntry
import app.codexremote.android.ui.theme.AppColors

/**
 * Tree node representing a directory entry with computed bounded indentation depth.
 */
internal data class DirectoryTreeNode(
    val entry: SubagentDirectoryEntry,
    val depth: Int,
)

/**
 * Builds an ordered descendant tree from directory entries.
 * Bounded indentation depth prevents excessive indentation on small screens.
 * Safe against missing ancestors and cyclic relations.
 */
internal fun buildDirectoryTree(entries: List<SubagentDirectoryEntry>): List<DirectoryTreeNode> {
    if (entries.isEmpty()) return emptyList()

    val entryMap = entries.associateBy { it.id }
    val childrenMap = mutableMapOf<String?, MutableList<SubagentDirectoryEntry>>()

    for (entry in entries) {
        val effectiveParentId = if (entry.parentId != null && entryMap.containsKey(entry.parentId)) {
            entry.parentId
        } else {
            null
        }
        childrenMap.getOrPut(effectiveParentId) { mutableListOf() }.add(entry)
    }

    val result = mutableListOf<DirectoryTreeNode>()
    val visited = mutableSetOf<String>()

    fun traverse(parentId: String?, currentDepth: Int) {
        val children = childrenMap[parentId] ?: return
        for (child in children) {
            if (visited.add(child.id)) {
                result.add(DirectoryTreeNode(entry = child, depth = currentDepth.coerceAtMost(4)))
                traverse(child.id, currentDepth + 1)
            }
        }
    }

    traverse(null, 0)

    // Fallback for any unvisited entries (e.g. cycle graphs)
    for (entry in entries) {
        if (visited.add(entry.id)) {
            result.add(DirectoryTreeNode(entry = entry, depth = 0))
        }
    }

    return result
}

/**
 * Shared reusable horizontal status filter bar for subagents.
 * Used in both SubagentDirectory and expanded SubagentStrip.
 * Horizontally scrollable to support narrow viewports and dynamic font scaling.
 */
@Composable
internal fun SubagentStatusFilterBar(
    selectedFilter: SubagentStatusFilter,
    onFilterSelected: (SubagentStatusFilter) -> Unit,
    counts: Map<SubagentStatusFilter, Int>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SubagentStatusFilter.entries.forEach { filter ->
            val isSelected = filter == selectedFilter
            val count = counts[filter] ?: 0
            val labelText = "${filter.label} ($count)"

            val shape = RoundedCornerShape(20.dp)
            val containerColor = if (isSelected) AppColors.primary else AppColors.surfaceContainerLow
            val contentColor = if (isSelected) AppColors.onPrimary else AppColors.onSurfaceVariant
            val borderStroke = if (isSelected) null else BorderStroke(1.dp, AppColors.outlineVariant.copy(alpha = 0.6f))

            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .then(
                        if (borderStroke != null) Modifier.border(borderStroke, shape) else Modifier
                    )
                    .background(containerColor)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onFilterSelected(filter) })
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .semantics {
                        contentDescription = "Filter ${filter.label}, $count items"
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = labelText,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = contentColor,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * SubagentDirectory displays discovered descendants with bounded parent relations,
 * status text, quiet capability text, and horizontal status filtering.
 * Tapping an entry inspects its detail.
 */
@Composable
fun SubagentDirectory(
    entries: List<SubagentDirectoryEntry>,
    loading: Boolean,
    error: String?,
    canLoadMore: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onInspect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val treeItems = remember(entries) { buildDirectoryTree(entries) }
    var selectedFilter by remember { mutableStateOf(SubagentStatusFilter.ALL) }

    val filterCounts = remember(entries) {
        SubagentStatusFilter.entries.associateWith { filter ->
            if (filter == SubagentStatusFilter.ALL) {
                entries.size
            } else {
                entries.count { filter.matches(it.status) }
            }
        }
    }

    val filteredTreeItems = remember(treeItems, selectedFilter) {
        if (selectedFilter == SubagentStatusFilter.ALL) {
            treeItems
        } else {
            treeItems.filter { selectedFilter.matches(it.entry.status) }
        }
    }

    val visibleIds = remember(filteredTreeItems) {
        filteredTreeItems.map { it.entry.id }.toSet()
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.background),
        color = AppColors.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: title with count, refresh action (minimum 48dp touch target)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(AppColors.surfaceContainerLow)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_extension),
                        contentDescription = null,
                        tint = AppColors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Subagents (${entries.size})",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    enabled = !loading,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Refresh subagents" }
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AppColors.primary
                        )
                    } else {
                        Text(
                            text = "↻",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.onSurface
                        )
                    }
                }
            }

            HorizontalDivider(color = AppColors.outlineVariant, thickness = 1.dp)

            // Progress bar
            if (loading && entries.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.primary,
                    trackColor = AppColors.surfaceContainerHigh
                )
            }

            // Error banner / retry
            if (error != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = error,
                        color = AppColors.error,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.error,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Retry", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Horizontal status filter tabs (retained under all filter / load states when entries exist)
            if (entries.isNotEmpty()) {
                SubagentStatusFilterBar(
                    selectedFilter = selectedFilter,
                    onFilterSelected = { selectedFilter = it },
                    counts = filterCounts
                )
                HorizontalDivider(color = AppColors.outlineVariant.copy(alpha = 0.35f), thickness = 0.5.dp)
            }

            // Content list or empty state
            if (treeItems.isEmpty() && !loading && error == null && !canLoadMore) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No subagents discovered yet",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.onSurface
                        )
                        Text(
                            text = "Discovered descendant subagents will appear here with parent relations, roles, and status as tasks run.",
                            fontSize = 13.sp,
                            color = AppColors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Top
                ) {
                    if (filteredTreeItems.isEmpty() && !loading && error == null) {
                        item(key = "no_filter_matches") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 36.dp, horizontal = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "No subagents matching \"${selectedFilter.label}\"",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppColors.onSurface,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "None of the discovered subagents match the selected status filter.",
                                        fontSize = 12.sp,
                                        color = AppColors.onSurfaceMuted,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        items(filteredTreeItems, key = { it.entry.id }) { item ->
                            DirectoryEntryRow(
                                item = item,
                                allEntries = entries,
                                visibleIds = visibleIds,
                                onInspect = onInspect
                            )
                        }
                    }

                    // Load more section (retained under all filters)
                    if (canLoadMore) {
                        item(key = "load_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = onLoadMore,
                                    enabled = !loading,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AppColors.surfaceContainerHigh,
                                        contentColor = AppColors.primary
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    if (loading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = AppColors.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text("Load more", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    item(key = "bottom_padding") {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

/**
 * Restrained grouped row representing an entry in the subagent directory.
 * Bounded parent relation is shown with clean tree connector and indentation.
 * Features quiet status text, quiet capability text without pill overload or repeated Inspect labels.
 * De-emphasizes IDs unless disambiguation is required.
 */
@Composable
private fun DirectoryEntryRow(
    item: DirectoryTreeNode,
    allEntries: List<SubagentDirectoryEntry>,
    onInspect: (String) -> Unit,
    modifier: Modifier = Modifier,
    visibleIds: Set<String> = emptySet(),
) {
    val entry = item.entry
    val shortId = shortThreadId(entry.id)
    val displayName = entry.name.ifBlank { "Subagent" }
    val indentWidth = (item.depth * 14).dp

    val parentId = entry.parentId
    val isParentAbsent = !parentId.isNullOrBlank() && parentId !in visibleIds
    val parentName = remember(parentId, isParentAbsent, allEntries) {
        if (isParentAbsent) {
            val parentEntry = allEntries.firstOrNull { it.id == parentId }
            parentEntry?.name?.trim()?.ifEmpty { null } ?: "parent"
        } else {
            null
        }
    }

    val needsDisambiguation = remember(entry.id, entry.name, allEntries) {
        val nameTrimmed = entry.name.trim()
        nameTrimmed.isBlank() ||
            nameTrimmed.equals("Subagent", ignoreCase = true) ||
            allEntries.count { it.name.trim().equals(nameTrimmed, ignoreCase = true) } > 1
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onInspect(entry.id) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics {
                    contentDescription = "Inspect $displayName, status ${subagentStatusLabel(entry.status)}"
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tree indentation and bounded relation indicator
            if (item.depth > 0) {
                Spacer(modifier = Modifier.width(indentWidth))
                Text(
                    text = "↳",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.onSurfaceMuted,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            // Subagent avatar replaces identity placeholder dot
            SubagentAvatar(
                threadId = entry.id,
                size = 28.dp
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Details column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Line 1: Display name, disambiguation ID if needed, and status text
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            text = displayName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (needsDisambiguation && shortId.isNotBlank()) {
                            Text(
                                text = "($shortId)",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AppColors.onSurfaceMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Quiet status text with separate status dot
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(subagentStatusColor(entry.status))
                        )
                        Text(
                            text = subagentStatusLabel(entry.status),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = subagentStatusColor(entry.status),
                            maxLines = 1
                        )
                    }
                }

                // Line 2: Role, capability text, and de-emphasized ID
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (parentName != null) {
                        Text(
                            text = "From: $parentName",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("·", fontSize = 11.sp, color = AppColors.onSurfaceMuted)
                    }

                    if (!entry.role.isNullOrBlank()) {
                        Text(
                            text = entry.role,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("·", fontSize = 11.sp, color = AppColors.onSurfaceMuted)
                    }

                    // Quiet capability text (no repeated chunky pill)
                    Text(
                        text = if (entry.canAcceptDirectInput == true) "Direct input" else "Read-only",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (entry.canAcceptDirectInput == true) AppColors.primary else AppColors.onSurfaceMuted,
                        maxLines = 1
                    )

                    if (!needsDisambiguation && shortId.isNotBlank()) {
                        Text("·", fontSize = 11.sp, color = AppColors.onSurfaceMuted)
                        Text(
                            text = "#$shortId",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AppColors.onSurfaceMuted.copy(alpha = 0.6f),
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Quiet subtle chevron indicator (no repeated "Inspect" labels)
            Text(
                text = "›",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.onSurfaceMuted
            )
        }

        // Quiet row separator
        HorizontalDivider(
            color = AppColors.outlineVariant.copy(alpha = 0.35f),
            thickness = 0.5.dp
        )
    }
}

/**
 * Full-window Compose Dialog presenting the subagent directory.
 * Features safe drawing padding, an accessible 48dp Close control in the header,
 * and reuses SubagentDirectory in weighted content.
 */
@Composable
fun SubagentDirectoryDialog(
    entries: List<SubagentDirectoryEntry>,
    loading: Boolean,
    error: String?,
    canLoadMore: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onInspect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .background(AppColors.background)
                .safeDrawingPadding(),
            color = AppColors.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with title and accessible 48dp Close control
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .background(AppColors.surfaceContainerLow)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "All subagents",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Close" }
                    ) {
                        Text(
                            text = "✕",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.onSurface
                        )
                    }
                }
                HorizontalDivider(color = AppColors.outlineVariant, thickness = 1.dp)

                // Reused SubagentDirectory in weight(1f)
                SubagentDirectory(
                    entries = entries,
                    loading = loading,
                    error = error,
                    canLoadMore = canLoadMore,
                    onRefresh = onRefresh,
                    onLoadMore = onLoadMore,
                    onInspect = onInspect,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}
