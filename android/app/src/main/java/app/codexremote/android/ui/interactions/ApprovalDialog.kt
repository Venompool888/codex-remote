package app.codexremote.android.ui.interactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.ui.theme.AppColors

data class ApprovalUiChoice(
    val id: String,
    val label: String,
    val enabled: Boolean = true
)

data class ApprovalUiState(
    val key: String,
    val title: String,
    val details: String,
    val choices: List<ApprovalUiChoice>,
    val isBusy: Boolean = false,
    val error: String? = null
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalDialog(
    state: ApprovalUiState?,
    onChoice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state == null) return

    val shape = RoundedCornerShape(16.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
        shape = shape,
        color = AppColors.surfaceContainer,
        border = BorderStroke(1.dp, AppColors.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Title
            Text(
                text = state.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.onSurface
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Scrollable, selectable details and error region
            SelectionContainer(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (!state.error.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppColors.error.copy(alpha = 0.12f))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = state.error,
                                fontSize = 13.sp,
                                color = AppColors.error
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = state.details,
                        fontSize = 14.sp,
                        color = AppColors.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Buttons wrap for 2x font
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.choices.forEach { choice ->
                    val isPrimary = choice.id == "allow" || state.choices.size == 1
                    Button(
                        onClick = { onChoice(choice.id) },
                        enabled = choice.enabled && !state.isBusy,
                        shape = RoundedCornerShape(8.dp),
                        colors = if (isPrimary) {
                            ButtonDefaults.buttonColors(
                                containerColor = AppColors.primary,
                                contentColor = AppColors.onPrimary
                            )
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = AppColors.surfaceContainerHighest,
                                contentColor = AppColors.onSurface
                            )
                        },
                        border = if (!isPrimary) BorderStroke(1.dp, AppColors.outlineVariant) else null
                    ) {
                        if (state.isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = if (isPrimary) AppColors.onPrimary else AppColors.onSurface
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                        }
                        Text(choice.label)
                    }
                }
            }
        }
    }
}
