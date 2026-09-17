package app.codexremote.android.ui.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codexremote.android.R
import app.codexremote.android.ui.theme.AppColors
import app.codexremote.android.ui.theme.CodexTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticLogDialog(
    report: String,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CodexTheme {
            val shape = RoundedCornerShape(16.dp)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.drawerScrim)
                    .safeDrawingPadding()
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
                            Text(
                                text = "Diagnostic log",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.semantics {
                                    text = AnnotatedString("Close")
                                    contentDescription = "Close"
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "Close",
                                    tint = AppColors.onSurfaceMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Report Content
                        if (report.isBlank()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppColors.background)
                                    .border(BorderStroke(1.dp, AppColors.outlineVariant), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_info),
                                        contentDescription = null,
                                        tint = AppColors.onSurfaceMuted,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "No diagnostic logs recorded",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = AppColors.onSurfaceMuted
                                    )
                                }
                            }
                        } else {
                            val lines = remember(report) { report.lines() }
                            val listState = rememberLazyListState()
                            LaunchedEffect(report) { listState.scrollToItem(lines.lastIndex.coerceAtLeast(0)) }

                            SelectionContainer(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AppColors.background)
                                        .border(BorderStroke(1.dp, AppColors.outlineVariant), RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                ) {
                                    items(count = lines.size) { index ->
                                        val line = lines[index]
                                        Text(
                                            text = line,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                            color = AppColors.onSurface,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Privacy wording
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_info),
                                contentDescription = null,
                                tint = AppColors.onSurfaceMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Logs are stored locally. Errors may contain host paths and should be reviewed before sharing.",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = AppColors.onSurfaceMuted
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Actions
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showClearConfirmation = true },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.surfaceContainerHighest,
                                    contentColor = AppColors.error
                                )
                            ) {
                                Text("Clear", fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onRefresh,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.surfaceContainerHighest,
                                    contentColor = AppColors.onSurface
                                )
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_refresh),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Refresh", fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onShare,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.primary,
                                    contentColor = AppColors.onPrimary
                                )
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_share_response),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (showClearConfirmation) {
                AlertDialog(
                    onDismissRequest = { showClearConfirmation = false },
                    title = {
                        Text(
                            text = "Clear diagnostic log?",
                            fontWeight = FontWeight.Bold,
                            color = AppColors.onSurface
                        )
                    },
                    text = {
                        Text(
                            text = "Are you sure you want to clear the diagnostic log? This action cannot be undone.",
                            fontSize = 14.sp,
                            color = AppColors.onSurfaceVariant
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showClearConfirmation = false
                                onClear()
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.error)
                        ) {
                            Text("Clear", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearConfirmation = false }) {
                            Text("Cancel", color = AppColors.onSurfaceVariant)
                        }
                    },
                    containerColor = AppColors.surfaceContainerHigh
                )
            }
        }
    }
}
