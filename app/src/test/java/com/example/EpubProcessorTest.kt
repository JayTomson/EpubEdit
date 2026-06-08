package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.util.EpubProcessor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpubProcessorTest {
    @Test
    fun testParse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val html = "<p><span style=\"font-size: 1.5em;\"><b>Послесловие команды</b></span></p>"
        val blocks = EpubProcessor.parseContentIntoBlocks(context, html)
        println("BLOCKS_SIZE: " + blocks.size)
        blocks.forEach { b ->
            if (b is com.example.util.ContentBlock.Text) {
                println("BLOCK_TEXT: " + b.htmlText)
            }
        }
    }
}
