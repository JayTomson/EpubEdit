package com.aistudio.epubedit.kqptxy

import com.mohamedrejeb.richeditor.model.RichTextState
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import com.aistudio.epubedit.kqptxy.ui.screens.handleHtmlAutoClose
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RichEditorTest {
    @Test
    fun testHtml() {
        val state = RichTextState()
        state.setHtml("<p><span style=\"font-size: 1.5em;\"><b>Послесловие команды</b></span></p>")
        println("TEST_RES: " + state.toHtml())
    }

    @Test
    fun testParagraphSpacing() {
        val html1 = "<p>Абзац 1</p><p><br></p><p>Абзац 2</p>"
        val html2 = "<p>Абзац 1</p><p>&nbsp;</p><p>Абзац 2</p>"
        val state1 = RichTextState().apply { setHtml(html1) }
        val state2 = RichTextState().apply { setHtml(html2) }
        println("HTML1_OUT: [${state1.toHtml()}]")
        println("HTML2_OUT: [${state2.toHtml()}]")
    }

    @Test
    fun testHandleHtmlAutoClose() {
        val oldState = TextFieldValue("<p")
        val newState = TextFieldValue("<p>", TextRange(3))
        val result = handleHtmlAutoClose(oldState, newState)
        assertEquals("<p></p>", result.text)
        assertEquals(3, result.selection.start)
    }

    @Test
    fun testHandleHtmlAutoCloseWithAttributes() {
        val oldState = TextFieldValue("<p class=\"italic\"")
        val newState = TextFieldValue("<p class=\"italic\">", TextRange(18))
        val result = handleHtmlAutoClose(oldState, newState)
        assertEquals("<p class=\"italic\"></p>", result.text)
        assertEquals(18, result.selection.start)
    }
}
