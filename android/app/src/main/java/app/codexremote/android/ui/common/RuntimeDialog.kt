package app.codexremote.android.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.codexremote.android.ui.theme.CodexTheme

data class RuntimeDialogAction(val label: String, val invoke: () -> Unit)
data class RuntimeDialogState(val title: String, val message: String, val actions: List<RuntimeDialogAction>)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuntimeDialog(state: RuntimeDialogState?, onDismiss: () -> Unit) {
    state ?: return
    CodexTheme {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(state.title) },
            text = { SelectionContainer { Text(state.message, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = {
                FlowRow(horizontalArrangement = Arrangement.End) {
                    state.actions.forEach { action -> TextButton(onClick = { onDismiss(); action.invoke() }) { Text(action.label) } }
                }
            })
    }
}
