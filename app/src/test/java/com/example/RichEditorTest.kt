package com.example

import com.mohamedrejeb.richeditor.model.RichTextState
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
}
