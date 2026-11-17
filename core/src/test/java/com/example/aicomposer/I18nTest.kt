package com.example.aicomposer

import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.Subject
import com.example.aicomposer.analysis.SubjectKind
import com.example.aicomposer.coach.CompositionRules
import com.example.aicomposer.i18n.I18n
import com.example.aicomposer.i18n.Lang
import com.example.aicomposer.i18n.tr
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class I18nTest {
    @After
    fun reset() { I18n.lang = Lang.EN }

    @Test
    fun locale_mapsChineseJapanese_andEverythingElseToEnglish() {
        assertEquals(Lang.ZH, I18n.fromLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals(Lang.ZH, I18n.fromLocale(Locale.TRADITIONAL_CHINESE))
        assertEquals(Lang.JA, I18n.fromLocale(Locale.JAPAN))
        assertEquals(Lang.EN, I18n.fromLocale(Locale.US))
        assertEquals(Lang.EN, I18n.fromLocale(Locale.GERMANY))
        assertEquals(Lang.EN, I18n.fromLocale(Locale.KOREA))
    }

    @Test
    fun tr_picksCurrentLanguage() {
        I18n.lang = Lang.ZH; assertEquals("中", tr("中", "日", "en"))
        I18n.lang = Lang.JA; assertEquals("日", tr("中", "日", "en"))
        I18n.lang = Lang.EN; assertEquals("en", tr("中", "日", "en"))
    }

    @Test
    fun ruleMessages_followLanguage() {
        val input = TestData.input(TestData.result(tilt = 3f))
        val msgs = Lang.entries.map { I18n.lang = it; CompositionRules.tilt.check(input)!!.message }
        assertEquals(3, msgs.toSet().size)
        assertTrue(msgs[0].contains("右低左高"))
        assertTrue(msgs[1].contains("右下がり"))
        assertTrue(msgs[2].contains("lower on the right"))
    }

    @Test
    fun subjectLabel_isTranslatedAtUse_andTextKeepsItsContent() {
        val s = Subject(NRect(0f, 0f, 1f, 1f), SubjectKind.TEXT, false, text = "ラーメン")
        I18n.lang = Lang.EN; val en = s.label
        I18n.lang = Lang.ZH; val zh = s.label
        assertTrue(en.contains("ラーメン") && zh.contains("ラーメン"))
        assertNotEquals(en, zh)
        assertTrue(s.isText)
    }
}
