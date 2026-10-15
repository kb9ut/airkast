package com.example.airkast.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.airkast.AirkastStation
import com.example.airkast.R
import com.example.airkast.TimeFilter
import com.example.airkast.ui.theme.AirkastMotion
import com.example.airkast.ui.theme.PillShape
import com.example.airkast.ui.theme.Spacing
import com.example.airkast.ui.theme.pressScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ────────────────────────────────────────────────────────────
// 放送局セレクタ
// ────────────────────────────────────────────────────────────

@Composable
fun StationSelector(
    stations: List<AirkastStation>,
    selectedStationId: String?,
    onStationSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedStation = stations.find { it.id == selectedStationId }
    val interactionSource = remember { MutableInteractionSource() }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = AirkastMotion.spatial(),
        label = "stationArrow",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .pressScale(interactionSource),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Radio,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(Spacing.l))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.station_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = selectedStation?.name ?: stringResource(R.string.station_placeholder),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selectedStation != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(arrowRotation),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            stations.forEach { station ->
                val isSelected = station.id == selectedStationId
                DropdownMenuItem(
                    text = {
                        Text(
                            text = station.name,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else null,
                    onClick = {
                        onStationSelected(station.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
// 日付セレクタ(横スクロール)
// ────────────────────────────────────────────────────────────

@Composable
fun DateSelector(
    dateList: List<Date>,
    selectedDate: Date,
    onDateSelected: (Date) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyFormat = remember { SimpleDateFormat("yyyyMMdd", Locale.JAPAN) }
    val dayFormat = remember { SimpleDateFormat("M/d", Locale.JAPAN) }
    val weekdayFormat = remember { SimpleDateFormat("E", Locale.JAPAN) }

    val selectedKey = keyFormat.format(selectedDate)
    val todayKey = keyFormat.format(Date())
    val selectedIndex = dateList.indexOfFirst { keyFormat.format(it) == selectedKey }

    val listState = rememberLazyListState()
    // 選択中の日付が見える位置までバネでスクロールする
    LaunchedEffect(selectedIndex, dateList.size) {
        if (selectedIndex >= 0) listState.animateScrollToItem((selectedIndex - 1).coerceAtLeast(0))
    }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        itemsIndexed(dateList) { _, date ->
            val key = keyFormat.format(date)
            DateChip(
                weekday = if (key == todayKey) {
                    stringResource(R.string.date_today)
                } else {
                    weekdayFormat.format(date)
                },
                day = dayFormat.format(date),
                selected = key == selectedKey,
                onClick = { onDateSelected(date) },
            )
        }
    }
}

@Composable
private fun DateChip(
    weekday: String,
    day: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.surfaceContainerHigh,
        animationSpec = AirkastMotion.effects(),
        label = "dateChipContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.onPrimary else colors.onSurface,
        animationSpec = AirkastMotion.effects(),
        label = "dateChipContent",
    )
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        selected = selected,
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .defaultMinSize(minWidth = 56.dp, minHeight = 64.dp)
            .pressScale(interactionSource),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.s),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = weekday,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = day,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ────────────────────────────────────────────────────────────
// 時間帯フィルタ
// ────────────────────────────────────────────────────────────

@Composable
fun TimeFilterChips(
    selectedFilter: TimeFilter,
    onFilterSelected: (TimeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        TimeFilter.entries.forEach { filter ->
            val selected = filter == selectedFilter
            val label = when (filter) {
                TimeFilter.EARLY_MORNING -> stringResource(R.string.filter_early_morning)
                TimeFilter.DAYTIME -> stringResource(R.string.filter_daytime)
                TimeFilter.NIGHT -> stringResource(R.string.filter_night)
                TimeFilter.MIDNIGHT -> stringResource(R.string.filter_midnight)
            }
            FilterChip(
                selected = selected,
                onClick = { onFilterSelected(filter) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                leadingIcon = if (selected) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else null,
                shape = PillShape,
            )
        }
    }
}
