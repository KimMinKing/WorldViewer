package dev.worldviewer.lookup

import org.junit.Assert.assertEquals
import org.junit.Test

class CedictPinyinTest {
    @Test
    fun `tone numbers become standard marks`() {
        assertEquals("jiāng hú", CedictPinyin.toToneMarks("jiang1 hu2"))
        assertEquals("nǚ ér", CedictPinyin.toToneMarks("nu:3 er2"))
        assertEquals("Ōuyáng Fēng", CedictPinyin.toToneMarks("Ou1yang2 Feng1"))
        assertEquals("ma", CedictPinyin.toToneMarks("ma5"))
    }
}
