package com.assistant.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** Behavior contract for markdown-to-speech text conversion. */
class SpeakableTextTest {

    @Test
    fun `plain text is unchanged`() {
        assertEquals("Hello world", speakableText("Hello world"))
    }

    @Test
    fun `fenced code blocks are dropped entirely`() {
        val markdown = "Before\n```py\nprint('x')\nprint('y')\n```\nAfter"
        assertEquals("Before After", speakableText(markdown))
    }

    @Test
    fun `inline code keeps its content without backticks`() {
        assertEquals("Use the id field here", speakableText("Use the `id` field here"))
    }

    @Test
    fun `bold and italic markers are removed`() {
        assertEquals("This is important and quiet", speakableText("**This** is *important* and _quiet_"))
    }

    @Test
    fun `links and images keep their visible text`() {
        assertEquals("See the docs logo now", speakableText("See [the docs](https:**example.com) ![logo](img.png) now"))
    }

    @Test
    fun `heading hashes and list markers are stripped`() {
        val markdown = "# Title\n## Subtitle\n- one\n* two\n3. three"
        assertEquals("Title Subtitle one two three", speakableText(markdown))
    }

    @Test
    fun `whitespace including newlines is collapsed`() {
        assertEquals("a b c", speakableText("a\n\n  b\t c"))
    }

    @Test
    fun `a full assistant answer becomes speakable prose`() {
        val markdown = "## Summary\nSee [docs](https:**example.com) and `code`.\n```\nfenced()\n```\n**Done** - item"
        assertEquals("Summary See docs and code. Done - item", speakableText(markdown))
    }
}
