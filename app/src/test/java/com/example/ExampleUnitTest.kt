package com.example

import com.example.util.DirectLinkConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun directLinkConverter_googleDrive() {
        val input = "https://drive.google.com/file/d/1a2b3c4d5e6f/view?usp=sharing"
        val result = DirectLinkConverter.convertToDirectLink(input)
        assertTrue(result is DirectLinkConverter.DirectConvertResult.Success)
        val success = result as DirectLinkConverter.DirectConvertResult.Success
        assertEquals("https://drive.google.com/uc?export=download&id=1a2b3c4d5e6f", success.directUrl)
    }

    @Test
    fun directLinkConverter_dropbox() {
        val input = "https://www.dropbox.com/s/abcdef12345/app.apk?dl=0"
        val result = DirectLinkConverter.convertToDirectLink(input)
        assertTrue(result is DirectLinkConverter.DirectConvertResult.Success)
        val success = result as DirectLinkConverter.DirectConvertResult.Success
        assertEquals("https://dl.dropboxusercontent.com/s/abcdef12345/app.apk?dl=1", success.directUrl)
    }
}
