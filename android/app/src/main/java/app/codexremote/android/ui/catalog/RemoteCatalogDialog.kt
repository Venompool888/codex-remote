package app.codexremote.android.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codexremote.android.R
import app.codexremote.android.presentation.catalog.CatalogCapabilityItem
import app.codexremote.android.presentation.catalog.CatalogController
import app.codexremote.android.presentation.catalog.ConnectedAppItem
import app.codexremote.android.ui.theme.AppColors

@Composable
fun RemoteCatalogDialog(
    controller: CatalogController,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value
    if (!state.isOpen) return

    Dialog(
        onDismissRequest = { controller.closeCatalog() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val shape = RoundedCornerShape(16.dp)

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(AppColors.drawerScrim)
                .imePadding()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
                shape = shape,
                color = AppColors.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Plugins and skills",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.onSurface
                            )
                            if (state.cwd.isNotBlank()) {
                                Text(
                                    text = state.cwd.substringAfterLast('/').ifBlank { state.cwd },
                                    fontSize = 12.sp,
                                    color = AppColors.onSurfaceMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(onClick = { controller.refresh() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "Refresh capabilities",
                                tint = AppColors.onSurfaceMuted
                            )
                        }
                        IconButton(onClick = { controller.closeCatalog() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Close",
                                tint = AppColors.onSurfaceMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Search field
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { controller.updateSearch(it) },
                        placeholder = { Text("Search plugins and skills", fontSize = 14.sp) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = null,
                                tint = AppColors.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (state.searchQuery.isNotBlank()) {
                                IconButton(onClick = { controller.updateSearch("") }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_attachment_remove),
                                        contentDescription = "Clear capability search",
                                        tint = AppColors.onSurfaceMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Search plugins and skills"
                            },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.primary,
                            unfocusedBorderColor = AppColors.outlineVariant,
                            focusedTextColor = AppColors.onSurface,
                            unfocusedTextColor = AppColors.onSurface
                        )
                    )

                    // Filter chips row
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.categories.forEach { category ->
                            val isSelected = category == state.activeKind
                            val chipShape = RoundedCornerShape(16.dp)
                            Box(
                                modifier = Modifier
                                    .clip(chipShape)
                                    .background(if (isSelected) AppColors.primary else AppColors.surfaceContainerHigh, chipShape)
                                    .clickable { controller.selectKind(category) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = category,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) AppColors.onPrimary else AppColors.onSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Body
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (state.isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Capabilities
                                if (state.filteredCapabilities.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "Capabilities",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AppColors.onSurfaceMuted,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                    items(state.filteredCapabilities, key = { it.id }) { item ->
                                        CapabilityRow(
                                            item = item,
                                            onClick = { controller.selectCapability(item) }
                                        )
                                    }
                                } else if (state.filteredApps.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (state.searchQuery.isNotBlank()) "No matching remote capabilities" else "No capabilities available",
                                                fontSize = 14.sp,
                                                color = AppColors.onSurfaceMuted
                                            )
                                        }
                                    }
                                }

                                // Catalog errors if any
                                if (state.catalogErrors.isNotEmpty()) {
                                    items(state.catalogErrors) { errorMsg ->
                                        Text(
                                            text = errorMsg,
                                            fontSize = 12.sp,
                                            color = AppColors.error,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                // Connected Apps · status
                                if (state.filteredApps.isNotEmpty()) {
                                    item {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Connected apps · status",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AppColors.onSurfaceMuted,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                    items(state.filteredApps, key = { it.name }) { app ->
                                        ConnectedAppRow(app = app)
                                    }
                                    item {
                                        Text(
                                            text = "Connected apps show connection status. Only capabilities marked enabled can be selected. Authorization and dependencies are managed on the host.",
                                            fontSize = 11.sp,
                                            color = AppColors.onSurfaceMuted,
                                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Explanation Dialog for Disabled / Unavailable Capabilities
        if (state.explanationDialogItem != null) {
            val item = state.explanationDialogItem
            AlertDialog(
                onDismissRequest = { controller.dismissExplanation() },
                title = { Text(item.name, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        val message = buildString {
                            if (item.detail.isNotBlank()) append(item.detail)
                            if (item.source.isNotBlank()) {
                                if (isNotBlank()) append("\n\n")
                                append(item.source)
                            }
                            if (isBlank()) append("This capability is managed and configured on the host.")
                        }
                        Text(message, fontSize = 14.sp, color = AppColors.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { controller.dismissExplanation() }) {
                        Text("OK", fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = AppColors.surfaceContainerHigh
            )
        }
    }
}

@Composable
private fun CapabilityRow(
    item: CatalogCapabilityItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val isEnabled = item.isEnabled
    val stateText = when (item.state.lowercase()) {
        "enabled" -> "Enabled"
        "disabled" -> "Disabled"
        "authorization_required" -> "Requires authorization"
        "missing_dependency" -> "Missing dependency"
        else -> "Unavailable"
    }
    val a11yDescription = "${item.name}. $stateText. ${item.description}"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.surfaceContainerLow, shape)
            .border(BorderStroke(1.dp, AppColors.outlineVariant), shape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = a11yDescription
            }
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(if (item.kind == "plugin") R.drawable.ic_extension else R.drawable.ic_plan_mode),
                contentDescription = null,
                tint = if (isEnabled) AppColors.primary else AppColors.onSurfaceMuted,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEnabled) AppColors.onSurface else AppColors.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isEnabled) Text(stateText, fontSize = 12.sp, color = AppColors.onSurfaceVariant)
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        fontSize = 12.sp,
                        color = AppColors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Enabled / Disabled indicator badge
            val badgeColor = if (isEnabled) AppColors.primaryContainer else AppColors.surfaceContainerHighest
            val textColor = if (isEnabled) AppColors.primary else AppColors.onSurfaceMuted
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isEnabled) "Add" else "Disabled",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
            }
        }
    }
}

@Composable
private fun ConnectedAppRow(
    app: ConnectedAppItem,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.surfaceContainerLow, shape)
            .border(BorderStroke(1.dp, AppColors.outlineVariant), shape)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_extension),
                contentDescription = null,
                tint = if (app.callable) AppColors.primary else AppColors.onSurfaceMuted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.onSurface
                )
                Text(
                    text = app.statusText,
                    fontSize = 11.sp,
                    color = AppColors.onSurfaceMuted
                )
            }
        }
    }
}
