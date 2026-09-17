package app.codexremote.android.ui.connections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.R
import app.codexremote.android.presentation.connections.AddConnectionController
import app.codexremote.android.ui.theme.AppColors

@Composable
fun AddConnectionScreen(
    controller: AddConnectionController,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value
    if (!state.isOpen) return

    BackHandler(enabled = !state.isBusy) {
        if (state.isConfirming) {
            controller.edit()
        } else {
            controller.dismiss()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.background)
    ) {
        if (state.isConfirming) {
            ConfirmationContent(
                controller = controller,
                onScan = onScan
            )
        } else {
            FormContent(
                controller = controller,
                onScan = onScan
            )
        }
    }
}

@Composable
private fun FormContent(
    controller: AddConnectionController,
    onScan: () -> Unit
) {
    val state = controller.uiState.value
    var codeVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { controller.dismiss() },
                enabled = !state.isBusy && !state.isScanning,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "Back",
                    tint = if (state.isBusy) AppColors.onSurfaceMuted else AppColors.onSurface
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Add Connection",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.onSurface
            )
        }

        // Scrollable Body
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Scan QR Code prominent top button
            Surface(
                onClick = onScan,
                enabled = !state.isBusy && !state.isScanning,
                shape = RoundedCornerShape(14.dp),
                color = AppColors.surfaceContainerLow,
                border = BorderStroke(1.dp, AppColors.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Button }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_photo),
                        contentDescription = null,
                        tint = AppColors.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Scan QR Code",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Quickly pair by scanning the terminal screen",
                            fontSize = 12.sp,
                            color = AppColors.onSurfaceVariant
                        )
                    }
                    if (state.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = AppColors.primary
                        )
                    }
                }
            }

            // Divider: Or enter manually
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(AppColors.outlineVariant)
                )
                Text(
                    text = "Or enter manually",
                    fontSize = 12.sp,
                    color = AppColors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(AppColors.outlineVariant)
                )
            }

            // Five Fields:
            // Field 1 & Field 3: Protocol switch + Port side-by-side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Protocol Switch
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Protocol",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    val isHttps = state.protocol.equals("https", ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppColors.surfaceContainerHigh)
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // HTTPS pill
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isHttps) AppColors.primary else AppColors.surfaceContainerHigh)
                                .clickable(enabled = !state.isBusy && !state.isScanning) {
                                    if (!isHttps) {
                                        controller.updateProtocol("https")
                                    }
                                }
                                .semantics {
                                    role = Role.RadioButton
                                    selected = isHttps
                                    contentDescription = "Protocol HTTPS"
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "HTTPS",
                                fontSize = 13.sp,
                                fontWeight = if (isHttps) FontWeight.Bold else FontWeight.Normal,
                                color = if (isHttps) AppColors.onPrimary else AppColors.onSurfaceVariant
                            )
                        }

                        // HTTP pill
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!isHttps) AppColors.primary else AppColors.surfaceContainerHigh)
                                .clickable(enabled = !state.isBusy && !state.isScanning) {
                                    if (isHttps) {
                                        controller.updateProtocol("http")
                                    }
                                }
                                .semantics {
                                    role = Role.RadioButton
                                    selected = !isHttps
                                    contentDescription = "Protocol HTTP"
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "HTTP",
                                fontSize = 13.sp,
                                fontWeight = if (!isHttps) FontWeight.Bold else FontWeight.Normal,
                                color = if (!isHttps) AppColors.onPrimary else AppColors.onSurfaceVariant
                            )
                        }
                    }
                }

                // Port field
                OutlinedTextField(
                    value = state.port,
                    onValueChange = { controller.updatePort(it) },
                    label = { Text("Port") },
                    placeholder = { Text(if (state.protocol.equals("http", ignoreCase = true)) "80" else "443") },
                    singleLine = true,
                    enabled = !state.isBusy && !state.isScanning,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.primary,
                        unfocusedBorderColor = AppColors.outlineVariant,
                        focusedTextColor = AppColors.onSurface,
                        unfocusedTextColor = AppColors.onSurface
                    )
                )
            }

            if (state.protocol == "http") {
                Text("HTTP is not encrypted. Use only on a trusted private network.",
                    fontSize = 12.sp, color = AppColors.onSurfaceVariant)
            }

            // Field 2: Domain or IP
            OutlinedTextField(
                value = state.host,
                onValueChange = { controller.updateHost(it) },
                label = { Text("Domain or IP") },
                placeholder = { Text("host.example or 192.168.1.100") },
                singleLine = true,
                enabled = !state.isBusy && !state.isScanning,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.primary,
                    unfocusedBorderColor = AppColors.outlineVariant,
                    focusedTextColor = AppColors.onSurface,
                    unfocusedTextColor = AppColors.onSurface
                )
            )

            // Field 4: Base path
            OutlinedTextField(
                value = state.basePath,
                onValueChange = { controller.updateBasePath(it) },
                label = { Text("Base path (optional)") },
                placeholder = { Text("/") },
                supportingText = {
                    Text(
                        text = "Service URL prefix, not filesystem path",
                        fontSize = 11.sp,
                        color = AppColors.onSurfaceVariant
                    )
                },
                singleLine = true,
                enabled = !state.isBusy && !state.isScanning,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.primary,
                    unfocusedBorderColor = AppColors.outlineVariant,
                    focusedTextColor = AppColors.onSurface,
                    unfocusedTextColor = AppColors.onSurface
                )
            )

            // Field 5: Pair Code
            OutlinedTextField(
                value = state.code,
                onValueChange = { controller.updateCode(it) },
                label = { Text("Pair Code") },
                placeholder = { Text("Enter pair code") },
                singleLine = true,
                enabled = !state.isBusy && !state.isScanning,
                visualTransformation = if (codeVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(
                        onClick = { codeVisible = !codeVisible },
                        enabled = !state.isBusy && !state.isScanning,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = if (codeVisible) "Hide pair code" else "Show pair code"
                            }
                    ) {
                        Text(
                            text = if (codeVisible) "Hide" else "Show",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.primary
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.primary,
                    unfocusedBorderColor = AppColors.outlineVariant,
                    focusedTextColor = AppColors.onSurface,
                    unfocusedTextColor = AppColors.onSurface
                )
            )

            // Server URL Preview card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.surfaceContainerLow)
                    .border(BorderStroke(1.dp, AppColors.outlineVariant), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Text(
                        text = "SERVER URL PREVIEW",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onSurfaceVariant,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = state.serverPreview.ifBlank { "—" },
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Bottom CTA Action Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            if (!state.error.isNullOrBlank()) {
                Text(
                    text = state.error,
                    color = AppColors.error,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }
            Button(
                onClick = { controller.submit() },
                enabled = !state.isBusy && !state.isScanning,
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.primary,
                    contentColor = AppColors.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = AppColors.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = "Pair & Connect",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ConfirmationContent(
    controller: AddConnectionController,
    onScan: () -> Unit
) {
    val state = controller.uiState.value
    var confirmCodeVisible by remember(state.isScanning, state.code.text) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { controller.edit() },
                enabled = !state.isBusy && !state.isScanning,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "Back",
                    tint = if (state.isBusy) AppColors.onSurfaceMuted else AppColors.onSurface
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Confirm Connection",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.onSurface
            )
        }

        // Scrollable Body
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Target Server URL Hero Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.surfaceContainerLow)
                    .border(BorderStroke(1.dp, AppColors.outlineVariant), RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "TARGET SERVER URL",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onSurfaceVariant,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.serverPreview.ifBlank { "—" },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.onSurface
                    )
                }
            }

            // Structured metadata card: host, port, path, protocol, masked code
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.surfaceContainerLow)
                    .border(BorderStroke(1.dp, AppColors.outlineVariant), RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StructuredRow(label = "Protocol", value = state.protocol.uppercase())
                    StructuredRow(label = "Host", value = state.host.text.ifBlank { "—" })
                    StructuredRow(label = "Port", value = state.port.text.ifBlank { "—" })
                    StructuredRow(label = "Base Path", value = state.basePath.text.ifBlank { "/" })

                    // Pair Code row with mask / unmask
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pair Code",
                                fontSize = 12.sp,
                                color = AppColors.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val codeRaw = state.code.text
                            val displayCode = if (confirmCodeVisible) {
                                codeRaw.ifBlank { "—" }
                            } else {
                                if (codeRaw.isBlank()) "—" else "•".repeat(12)
                            }
                            Text(
                                text = displayCode,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                color = AppColors.onSurface
                            )
                        }
                        TextButton(
                            onClick = { confirmCodeVisible = !confirmCodeVisible },
                            enabled = !state.isBusy && !state.isScanning,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = if (confirmCodeVisible) "Hide pair code" else "Show pair code"
                                }
                        ) {
                            Text(
                                text = if (confirmCodeVisible) "Hide" else "Show",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.primary
                            )
                        }
                    }
                }
            }

            // Security Notice Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.surfaceContainerHigh)
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        painter = painterResource(R.drawable.ic_gpp_maybe_outline),
                        contentDescription = null,
                        tint = AppColors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (state.protocol == "http") "HTTP is not encrypted. Check the server before pairing." else "Check the server details before pairing.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = AppColors.onSurfaceVariant
                    )
                }
            }

            // Auxiliary Actions: Edit details and Scan again
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { controller.edit() },
                    enabled = !state.isBusy && !state.isScanning,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AppColors.onSurface
                    ),
                    border = BorderStroke(1.dp, AppColors.outlineVariant),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(
                        text = "Edit details",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OutlinedButton(
                    onClick = onScan,
                    enabled = !state.isBusy && !state.isScanning,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AppColors.onSurface
                    ),
                    border = BorderStroke(1.dp, AppColors.outlineVariant),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    if (state.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AppColors.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = "Scan again",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Bottom CTA Action Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            if (!state.error.isNullOrBlank()) {
                Text(
                    text = state.error,
                    color = AppColors.error,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }
            Button(
                onClick = { controller.submit() },
                enabled = !state.isBusy && !state.isScanning,
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.primary,
                    contentColor = AppColors.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = AppColors.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = "Confirm & Connect",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StructuredRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = AppColors.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            textAlign = TextAlign.End,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = AppColors.onSurface
        )
    }
}
