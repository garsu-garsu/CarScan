package com.bruni.carscan.feature.dashboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The arithmetic behind drag-to-reorder, tested away from the grid that feeds it.
 *
 * A two-by-two grid of 100px tiles with 12px between them, which is what
 * `GridCells.Adaptive(160.dp)` lays out on a phone:
 *
 * ```
 *   0 (0,0)      1 (112,0)
 *   2 (0,112)    3 (112,112)
 * ```
 */
class TileDragTest {

    private val grid = listOf(
        TileBounds(index = 0, left = 0, top = 0, width = 100, height = 100),
        TileBounds(index = 1, left = 112, top = 0, width = 100, height = 100),
        TileBounds(index = 2, left = 0, top = 112, width = 100, height = 100),
        TileBounds(index = 3, left = 112, top = 112, width = 100, height = 100),
    )

    @Test
    fun aTileSittingInItsOwnSlotMovesNothing() {
        assertNull(dropTarget(grid, dragged = grid[0], offset = Offset.Zero, movedFrom = null))
    }

    @Test
    fun aTileNudgedButStillOverItsOwnSlotMovesNothing() {
        // Half a slot is not a reorder. Only the tile the centre has actually landed on counts.
        assertNull(dropTarget(grid, dragged = grid[0], offset = Offset(40f, 0f), movedFrom = null))
    }

    @Test
    fun aTileOverTheGapBetweenSlotsMovesNothing() {
        // Centre at x=106: past tile 0, not yet onto tile 1. Reordering off the nearest slot
        // instead would make the tiles either side of a gap swap as the finger crosses it.
        assertNull(dropTarget(grid, dragged = grid[0], offset = Offset(50f, 0f), movedFrom = null))
    }

    @Test
    fun aTileDraggedOntoTheNextSlotTakesThatIndex() {
        val target = dropTarget(grid, dragged = grid[0], offset = Offset(112f, 0f), movedFrom = null)
        assertEquals(1, target?.index)
    }

    @Test
    fun theTranslationIsRebasedByExactlyOneSlotHorizontally() {
        // The tile is about to be *placed* in slot 1, so slot 1's worth of translation has to
        // come off in the same breath. Everything else the finger has travelled stays.
        val target = dropTarget(grid, dragged = grid[0], offset = Offset(130f, 5f), movedFrom = null)
        assertEquals(DropTarget(index = 1, offset = Offset(18f, 5f)), target)
    }

    @Test
    fun theTranslationIsRebasedByExactlyOneSlotVertically() {
        val target = dropTarget(grid, dragged = grid[0], offset = Offset(3f, 120f), movedFrom = null)
        assertEquals(DropTarget(index = 2, offset = Offset(3f, 8f)), target)
    }

    @Test
    fun aSecondMoveIsRefusedWhileTheGridStillReportsTheOldOrder() {
        // The frame after a reorder, the layout has not caught up: it still places the dragged
        // tile in the slot it just left. Acting on that reads the tile's old index and swaps it
        // straight back, and the tile oscillates under a finger that has not moved.
        assertNull(
            dropTarget(
                grid,
                dragged = grid[0],
                offset = Offset(112f, 0f),
                movedFrom = IntOffset(0, 0),
            ),
        )
    }

    @Test
    fun draggingResumesOnceTheGridHasPlacedTheTileSomewhereNew() {
        // Same drag, but the tile is now laid out in slot 1 — the layout has caught up, so the
        // guard from the previous move no longer applies.
        val target = dropTarget(
            grid,
            dragged = grid[1],
            offset = Offset(0f, 112f),
            movedFrom = IntOffset(0, 0),
        )
        assertEquals(DropTarget(index = 3, offset = Offset(0f, 0f)), target)
    }

    @Test
    fun aTileDraggedOffTheGridEntirelyMovesNothing() {
        assertNull(
            dropTarget(grid, dragged = grid[0], offset = Offset(0f, 400f), movedFrom = null),
        )
    }
}
