package com.example.airkast.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.airkast.R
import com.example.airkast.ui.theme.MinTouchTarget
import com.example.airkast.ui.theme.PillShape
import com.example.airkast.ui.theme.Spacing
import com.example.airkast.ui.theme.TabularNumbers

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
// ターボ倍速設定
// ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TurboSpeedDialog(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var sliderValue by remember { mutableFloatStateOf(currentSpeed) }
    val sliderDescription = stringResource(R.string.turbo_slider)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.turbo_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.turbo_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.l))
                Text(
                    text = stringResource(R.string.player_turbo_value, sliderValue.toInt()),
                    style = MaterialTheme.typography.headlineLarge.merge(TabularNumbers),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 10f..200f,
                    steps = 18, // 10刻み: 10,20,...,200 → 19段階 → steps = 18
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .semantics { contentDescription = sliderDescription },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("10x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("200x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(Spacing.l))
                Text(
                    text = stringResource(R.string.turbo_presets),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.s))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    listOf(10f, 30f, 50f, 100f, 200f).forEach { preset ->
                        FilterChip(
                            selected = sliderValue == preset,
                            onClick = { sliderValue = preset },
                            label = { Text("${preset.toInt()}x") },
                            shape = PillShape,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSpeedSelected(sliderValue)
                    onDismiss()
                },
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) { Text(stringResource(R.string.dialog_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
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

// ────────────────────────────────────────────────────────────
// エリア選択
// ────────────────────────────────────────────────────────────

private val Areas = listOf(
    "JP13" to "東京",
    "JP27" to "大阪",
    "JP25" to "滋賀",
    "JP24" to "三重",
    "JP36" to "徳島",
    "JP01" to "北海道",
    "JP04" to "宮城",
    "JP23" to "愛知",
    "JP26" to "京都",
    "JP28" to "兵庫",
    "JP34" to "広島",
    "JP40" to "福岡",
)

@Composable
fun AreaSelectionDialog(
    currentAreaId: String,
    onAreaSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.area_title)) },
        text = {
            Column {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.m),
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.m),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.area_warning),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                LazyColumn {
                    items(Areas) { (areaId, areaName) ->
                        RadioRow(
                            label = "$areaName ($areaId)",
                            selected = currentAreaId == areaId,
                            onClick = { onAreaSelected(areaId) },
                        )
                    }
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
