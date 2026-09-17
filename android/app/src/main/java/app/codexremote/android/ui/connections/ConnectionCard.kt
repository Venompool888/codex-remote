package app.codexremote.android.ui.connections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.R
import app.codexremote.android.RemoteConnection
import app.codexremote.android.RemoteProject
import app.codexremote.android.ui.theme.AppColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectionCard(
    connection: RemoteConnection,
    isConnected: Boolean,
    isConnecting: Boolean,
    activeProjectId: String?,
    onSelectProject: (RemoteProject) -> Unit,
    onReconnect: (String) -> Unit,
    onPair: (String) -> Unit,
    onAddProject: (String) -> Unit,
    onDelete: (RemoteConnection) -> Unit,
    onEdit: (RemoteConnection) -> Unit = {},
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onToggleSelected: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.surfaceContainerLow, shape)
            .border(BorderStroke(1.dp, AppColors.outlineVariant), shape)
            .combinedClickable(onClick = { if (selectionMode) onToggleSelected() else onEdit(connection) }, onLongClick = { if (!selectionMode) showMenu = true })
            .semantics { contentDescription = "Connection ${connection.name}" }
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionMode) Checkbox(checked = isSelected, onCheckedChange = { onToggleSelected() })
                // Status dot
                val dotColor = if (isConnected) AppColors.success else if (isConnecting) AppColors.warning else AppColors.outline
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connection.name.ifBlank { connection.serverUrl },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = connection.serverUrl,
                        fontSize = 12.sp,
                        color = AppColors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 3-dots menu button
                if (!selectionMode) Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.semantics {
                            contentDescription = "Options for ${connection.name.ifBlank { connection.serverUrl }}"
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = null,
                            tint = AppColors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(AppColors.surfaceContainerHigh)
                    ) {
                        DropdownMenuItem(text = { Text("Select") }, onClick = { showMenu = false; onSelect() })
                        DropdownMenuItem(
                            text = { Text("Reconnect", color = AppColors.onSurface) },
                            onClick = {
                                showMenu = false
                                onReconnect(connection.serverUrl)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Pair with host", color = AppColors.onSurface) },
                            onClick = {
                                showMenu = false
                                onPair(connection.serverUrl)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add remote project", color = AppColors.onSurface) },
                            onClick = {
                                showMenu = false
                                onAddProject(connection.serverUrl)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings & credentials", color = AppColors.onSurface) },
                            onClick = {
                                showMenu = false
                                onEdit(connection)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = AppColors.error) },
                            onClick = {
                                showMenu = false
                                onDelete(connection)
                            }
                        )
                    }
                }
            }

            // Projects list
            if (!selectionMode && connection.projects.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Projects",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.onSurfaceMuted
                )
                Spacer(modifier = Modifier.height(6.dp))

                connection.projects.forEach { project ->
                    val isActive = project.id == activeProjectId
                    val projShape = RoundedCornerShape(8.dp)
                    val projBg = if (isActive) AppColors.surfaceContainerHigh else AppColors.surfaceContainer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(projShape)
                            .background(projBg, projShape)
                            .clickable { onSelectProject(project) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = null,
                            tint = if (isActive) AppColors.primary else AppColors.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = project.name,
                                fontSize = 14.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                color = AppColors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = project.workspace,
                                fontSize = 11.sp,
                                color = AppColors.onSurfaceMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isActive) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = "Active project",
                                tint = AppColors.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
