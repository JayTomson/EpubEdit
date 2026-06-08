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
}
