package dev.worldviewer.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTapZonesTest {
    @Test fun `uses vertical forty twenty forty zones`() {
        assertEquals(ReaderTapAction.PREVIOUS_PAGE, ReaderTapZones.action(0f, 1000))
        assertEquals(ReaderTapAction.PREVIOUS_PAGE, ReaderTapZones.action(399f, 1000))
        assertEquals(ReaderTapAction.TOGGLE_CONTROLS, ReaderTapZones.action(400f, 1000))
        assertEquals(ReaderTapAction.TOGGLE_CONTROLS, ReaderTapZones.action(600f, 1000))
        assertEquals(ReaderTapAction.NEXT_PAGE, ReaderTapZones.action(601f, 1000))
        assertEquals(ReaderTapAction.NEXT_PAGE, ReaderTapZones.action(1000f, 1000))
    }
}
