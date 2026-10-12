package pt.tiago.bubblenight.ui

import org.junit.Assert.*
import org.junit.Test

class PresentationRulesTest {
    @Test fun labelsRemainPositionableOnTinyBoardsAndWithOversizedText() {
        for ((available, label) in listOf(140f to 160f, 0f to 30f, 6f to 2f, 320f to 100f)) {
            val position = floatingLabelPosition(70f, available, label)
            assertTrue(position.isFinite())
            assertTrue(position >= 0f)
            assertTrue(position <= (available - label).coerceAtLeast(0f))
        }
    }
}
