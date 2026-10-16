package com.example.airkast.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.example.airkast.R
import com.example.airkast.ui.theme.MinTouchTarget
import com.example.airkast.ui.theme.Spacing

/** ラジオ選択行(48dp 以上、行全体がタップ対象)。 */
@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Spacing.m))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ────────────────────────────────────────────────────────────
// ダウンロード分割サイズ設定
// ────────────────────────────────────────────────────────────

@Composable
fun DownloadChunkSizeDialog(
    currentChunkSize: Int,
    onChunkSizeSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        15 to stringResource(R.string.chunk_safe),
        60 to stringResource(R.string.chunk_recommended),
        300 to stringResource(R.string.chunk_fast),
        7200 to stringResource(R.string.chunk_all),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chunk_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.chunk_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.m))
                options.forEach { (value, label) ->
                    RadioRow(
                        label = label,
                        selected = currentChunkSize == value,
                        onClick = {
                            onChunkSizeSelected(value)
                            onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_close))
            }
        },
    )
}
