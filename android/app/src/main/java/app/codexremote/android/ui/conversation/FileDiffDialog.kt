package app.codexremote.android.ui.conversation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codexremote.android.FileDiffDetail
import app.codexremote.android.R
import app.codexremote.android.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDiffDialog(
    files: List<FileDiffDetail>,
    selectedPath: String?,
    onSelectFile: (String) -> Unit,
    onCopyPatch: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (files.isEmpty()) return

    val currentFile = files.firstOrNull { it.path == selectedPath } ?: files.first()
    var copied by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val shape = RoundedCornerShape(16.dp)

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(AppColors.drawerScrim)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f),
                shape = shape,
                color = AppColors.surfaceContainer
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "File diff",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.onSurface
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    onCopyPatch(currentFile.patch)
                                    copied = true
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.surfaceContainerHighest,
                                    contentColor = AppColors.primary
                                ),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(
                                    painter = painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (copied) "Copied" else "Copy patch", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "Close",
                                    tint = AppColors.onSurfaceMuted
                                )
                            }
                        }
                    }

                    // File selector tabs if multiple files
                    if (files.size > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            files.forEach { file ->
                                val isSelected = file.path == currentFile.path
                                val tabShape = RoundedCornerShape(8.dp)
                                Row(
                                    modifier = Modifier
                                        .clip(tabShape)
                                        .background(if (isSelected) AppColors.primary else AppColors.surfaceContainerHigh, tabShape)
                                        .clickable { onSelectFile(file.path) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = file.displayName,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) AppColors.onPrimary else AppColors.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (file.additions > 0) {
                                        Text("+${file.additions}", fontSize = 11.sp, color = if (isSelected) AppColors.onPrimary else Color(0xFF4ADE80))
                                    }
                                    if (file.deletions > 0) {
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("-${file.deletions}", fontSize = 11.sp, color = if (isSelected) AppColors.onPrimary else Color(0xFFF87171))
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = AppColors.outlineVariant, thickness = 1.dp)
                    }

                    // Diff lines viewer
                    val lines = remember(currentFile.patch) { currentFile.patch.lines() }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(AppColors.background)
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        items(lines) { line ->
                            val lineBg = when {
                                line.startsWith("+") && !line.startsWith("+++") -> Color(0x224ADE80)
                                line.startsWith("-") && !line.startsWith("---") -> Color(0x22F87171)
                                line.startsWith("@@") -> Color(0x18000000)
                                else -> Color.Transparent
                            }
                            val textColor = when {
                                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF15803D)
                                line.startsWith("-") && !line.startsWith("---") -> Color(0xFFB91C1C)
                                line.startsWith("@@") -> AppColors.primary
                                else -> AppColors.onSurface
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(lineBg)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
