package com.bruni.carscan.feature.dashboard

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset

/**
 * Where the tile currently under the finger has been dragged to.
 *
 * [offset] changes at pointer rate — every frame of a drag — so **nothing may read it during
 * composition**. It is read only inside a `graphicsLayer` block, which re-runs in the draw phase
 * and never recomposes the gauge it is translating; at 20 Hz of live data the grid has no
 * recompositions to spare. [id] is the deliberate exception: it is read in composition, for
 * z-order and to hand placement over to the finger, but it changes exactly twice per drag.
 */
internal class TileDrag {
    var id: String? by mutableStateOf(null)
    var offset by mutableStateOf(Offset.Zero)

    /**
     * Where the dragged tile was laid out when the last reorder was emitted.
     *
     * A reorder goes through the reducer, so the grid still reports the *old* order for a frame
     * or so afterwards. Hit-testing against that stale layout reads the dragged tile's old index
     * and swaps it straight back, which makes the tile oscillate under a finger that has not
     * moved. So a drag ignores the layout until the tile has actually been placed somewhere new.
     *
     * Not a snapshot state: only the gesture callbacks ever read it.
     */
    var movedFrom: IntOffset? = null

    fun clear() {
        id = null
        offset = Offset.Zero
        movedFrom = null
    }
}

/** A tile's laid-out slot, in the grid's own coordinates. */
internal data class TileBounds(
    val index: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** The tile a drag has landed on, and the translation that keeps it under the finger. */
internal data class DropTarget(val index: Int, val offset: Offset)

/**
 * Hit-tests a drag against the laid-out grid: which tile is the dragged one now covering?
 *
 * Returns null — meaning *do nothing yet* — in the three cases that must not reorder anything:
 * the layout has not yet caught up with the previous move (see [TileDrag.movedFrom]), the tile
 * is over a gap between slots, or it is still over its own.
 *
 * The returned offset is rebased. The reorder drops the tile into the target's slot, so exactly
 * that many pixels have to come off the translation in the same breath, or the tile lurches a
 * whole slot sideways the instant the move lands.
 */
internal fun dropTarget(
    items: List<TileBounds>,
    dragged: TileBounds,
    offset: Offset,
    movedFrom: IntOffset?,
): DropTarget? {
    if (IntOffset(dragged.left, dragged.top) == movedFrom) return null

    val x = dragged.left + offset.x + dragged.width / 2f
    val y = dragged.top + offset.y + dragged.height / 2f
    val over = items.firstOrNull {
        x >= it.left && x < it.left + it.width && y >= it.top && y < it.top + it.height
    } ?: return null
    if (over.index == dragged.index) return null

    return DropTarget(
        index = over.index,
        offset = Offset(
            offset.x - (over.left - dragged.left),
            offset.y - (over.top - dragged.top),
        ),
    )
}

/**
 * Long-press to pick a tile up, drag to put it somewhere else.
 *
 * This modifier is only ever in the chain while the dashboard is in edit mode. In normal driving
 * mode there is no drag detector on a gauge at all, so no amount of leaning on the screen over a
 * pothole can start rearranging the dashboard.
 *
 * There is no edge auto-scroll: moving a tile past the fold takes two drags rather than one.
 */
internal fun Modifier.reorderable(
    id: String,
    grid: LazyGridState,
    drag: TileDrag,
    onMove: (from: Int, to: Int) -> Unit,
): Modifier = pointerInput(id) {
    detectDragGesturesAfterLongPress(
        onDragStart = {
            drag.id = id
            drag.offset = Offset.Zero
            drag.movedFrom = null
        },
        onDragEnd = { drag.clear() },
        onDragCancel = { drag.clear() },
        onDrag = { change, delta ->
            change.consume()
            drag.offset += delta

            val items = grid.layoutInfo.visibleItemsInfo
            val dragged = items.firstOrNull { it.key == id }?.bounds()
            val target = dragged?.let {
                dropTarget(items.map { info -> info.bounds() }, it, drag.offset, drag.movedFrom)
            }
            if (dragged != null && target != null) {
                drag.movedFrom = IntOffset(dragged.left, dragged.top)
                drag.offset = target.offset
                onMove(dragged.index, target.index)
            }
        },
    )
}

private fun LazyGridItemInfo.bounds() =
    TileBounds(index, offset.x, offset.y, size.width, size.height)
