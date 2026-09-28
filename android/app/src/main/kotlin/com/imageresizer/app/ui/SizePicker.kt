package com.imageresizer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/** Unit shown by the size wheel. */
enum class SizeUnit { KB, MB }

/**
 * Curated wheel values — every entry is a valid target under
 * ProductRules.minimumTargetBytes (100 KB), so an invalid state cannot exist
 * and there is nothing to validate or reject in the UI.
 */
object TargetSizes {
    val kbValues: List<Int> = listOf(100, 125, 150, 200, 250, 300, 400, 500, 750, 1000)
    val mbValues: List<Int> = listOf(1, 2, 3, 4, 5, 6, 8, 10, 15, 20, 25, 30, 50, 75, 100)

    val defaultUnit = SizeUnit.MB
    const val defaultValue = 5

    fun values(unit: SizeUnit): List<Int> = when (unit) {
        SizeUnit.KB -> kbValues
        SizeUnit.MB -> mbValues
    }

    fun bytes(unit: SizeUnit, value: Int): Long = when (unit) {
        SizeUnit.KB -> value.toLong() * 1024
        SizeUnit.MB -> value.toLong() * 1024 * 1024
    }

    /** Wheel entry closest to [bytes]; used to snap when the unit switches. */
    fun nearest(unit: SizeUnit, bytes: Long): Int =
        values(unit).minByOrNull { abs(bytes(unit, it) - bytes) } ?: values(unit).last()
}

private val WheelItemHeight = 40.dp
private const val WheelVisibleRows = 5

/**
 * Scroll-wheel size picker: the value in the center band is the selection.
 * The segmented control to the right switches KB/MB, converting the current
 * target and snapping to the destination wheel's nearest value.
 */
@Composable
fun SizePicker(
    unit: SizeUnit,
    value: Int,
    onChange: (SizeUnit, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SizeWheel(
            unit = unit,
            value = value,
            onValueChange = { onChange(unit, it) },
            modifier = Modifier.weight(1f),
        )
        UnitSelector(unit = unit) { newUnit ->
            if (newUnit != unit) {
                onChange(newUnit, TargetSizes.nearest(newUnit, TargetSizes.bytes(unit, value)))
            }
        }
    }
}

@Composable
private fun SizeWheel(
    unit: SizeUnit,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val values = TargetSizes.values(unit)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = values.indexOf(value).coerceAtLeast(0),
    )
    // Default snap position is SnapPosition.Center: the settled item's center lands
    // exactly on (viewportStartOffset + viewportEndOffset) / 2, which is also how the
    // selection is derived below — both are the container's true center.
    val fling = rememberSnapFlingBehavior(listState)

    LaunchedEffect(listState, values) {
        // Re-center first (a unit switch replaces `values`): the observer below must
        // never see the position left over from the previous wheel.
        listState.scrollToItem(values.indexOf(value).coerceAtLeast(0))
        var lastIndex: Int? = null
        snapshotFlow {
            val layout = listState.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2 - center) }
                ?.index
        }
            .distinctUntilChanged()
            .collect { index ->
                if (index != null && index in values.indices && index != lastIndex) {
                    lastIndex = index
                    onValueChange(values[index])
                }
            }
    }

    val currentIndex = values.indexOf(value)
    Box(
        modifier = modifier.height(WheelItemHeight * WheelVisibleRows),
        contentAlignment = Alignment.Center,
    ) {
        // Selection band behind the wheel.
        Box(
            Modifier
                .fillMaxWidth()
                .height(WheelItemHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = WheelItemHeight * (WheelVisibleRows / 2)),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(values) { index, item ->
                val distance = abs(index - currentIndex)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WheelItemHeight),
                ) {
                    Text(
                        text = item.toString(),
                        style = when (distance) {
                            0 -> MaterialTheme.typography.titleLarge
                            1 -> MaterialTheme.typography.bodyLarge
                            else -> MaterialTheme.typography.bodyMedium
                        },
                        fontWeight = if (distance == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = when (distance) {
                            0 -> MaterialTheme.colorScheme.primary
                            1 -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UnitSelector(
    unit: SizeUnit,
    onUnitChange: (SizeUnit) -> Unit,
) {
    val options = SizeUnit.entries
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == unit,
                onClick = { onUnitChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(option.name)
            }
        }
    }
}
