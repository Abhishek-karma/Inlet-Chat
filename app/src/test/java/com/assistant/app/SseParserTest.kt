package com.assistant.app

import com.assistant.app.llm.SseEvent
import com.assistant.app.llm.SseParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavior contract for the incremental SSE parser: arbitrary chunk
 * boundaries, line endings, comments, and event/data field handling.
 */
class SseParserTest {

    @Test
    fun `single frame parses`() {
        val parser = SseParser()
        assertEquals(listOf(SseEvent(null, "hello")), parser.parseSse("data: hello\n\n"))
    }

    @Test
    fun `frame split across two feeds parses once complete`() {
        val parser = SseParser()
        assertEquals(emptyList<SseEvent>(), parser.parseSse("data: {\"chan"))

        assertEquals(
            listOf(SseEvent(null, "{\"channels\": 1}")),
            parser.parseSse("nels\": 1}\n\n"),
        )
    }

    @Test
    fun `data DONE passes through as an event`() {
        val parser = SseParser()
        assertEquals(listOf(SseEvent(null, "[DONE]")), parser.parseSse("data: [DONE]\n\n"))
    }

    @Test
    fun `CRLF frames parse`() {
        val parser = SseParser()
        assertEquals(listOf(SseEvent(null, "hello")), parser.parseSse("data: hello\r\n\r\n"))
    }

    @Test
    fun `trailing CR across feeds waits for the next chunk`() {
        val parser = SseParser()
        assertEquals(emptyList<SseEvent>(), parser.parseSse("data: a\r"))

        // The LF completing the CRLF arrives in the next chunk: the line is
        // complete but the frame is not terminated yet.
        assertEquals(emptyList<SseEvent>(), parser.parseSse("\n"))

        assertEquals(listOf(SseEvent(null, "a")), parser.parseSse("\n"))
    }

    @Test
    fun `comment lines are skipped`() {
        val parser = SseParser()
        assertEquals(
            listOf(SseEvent(null, "x")),
            parser.parseSse(": ping\ndata: x\n\n"),
        )
        assertEquals(emptyList<SseEvent>(), parser.parseSse(": keepalive\n\n"))
    }

    @Test
    fun `event field is surfaced`() {
        val parser = SseParser()
        assertEquals(
            listOf(SseEvent("error", "boom")),
            parser.parseSse("event: error\ndata: boom\n\n"),
        )
    }

    @Test
    fun `empty payloads are skipped`() {
        val parser = SseParser()
        assertEquals(emptyList<SseEvent>(), parser.parseSse("data:\n\n"))
        assertEquals(emptyList<SseEvent>(), parser.parseSse("event: ping\n\n"))
    }

    @Test
    fun `multiple frames parse in order`() {
        val parser = SseParser()
        assertEquals(
            listOf(SseEvent(null, "a"), SseEvent(null, "b")),
            parser.parseSse("data:a\n\ndata: b\n\n"),
        )
    }

    @Test
    fun `unknown fields ignored and data lines joined`() {
        val parser = SseParser()
        assertEquals(
            listOf(SseEvent(null, "line1\nline2")),
            parser.parseSse("id: 7\nretry: 100\ndata: line1\ndata: line2\n\n"),
        )
    }

    @Test
    fun `leading BOM is stripped`() {
        val parser = SseParser()
        assertEquals(
            listOf(SseEvent(null, "hello")),
            parser.parseSse("\uFEFFdata: hello\n\n"),
        )
    }

    @Test
    fun `final frame without blank line is flushed at eof`() {
        val parser = SseParser()
        assertEquals(emptyList<SseEvent>(), parser.parseSse("data: hello\n"))

        assertEquals(listOf(SseEvent(null, "hello")), parser.flush())
    }

    @Test
    fun `flush processes a buffered unterminated line`() {
        val parser = SseParser()
        assertEquals(emptyList<SseEvent>(), parser.parseSse("data: tail\r"))

        assertEquals(listOf(SseEvent(null, "tail")), parser.flush())
    }

    @Test
    fun `flush is empty after a complete stream and idempotent`() {
        val parser = SseParser()
        assertEquals(listOf(SseEvent(null, "hello")), parser.parseSse("data: hello\n\n"))

        assertEquals(emptyList<SseEvent>(), parser.flush())
        assertEquals(emptyList<SseEvent>(), parser.flush())
    }
}
