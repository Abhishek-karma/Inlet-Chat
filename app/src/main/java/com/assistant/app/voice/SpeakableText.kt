package com.assistant.app.voice

/**
 * Converts assistant markdown to speakable plain text: fenced code blocks are
 * dropped entirely, inline code keeps its content, emphasis markers are
 * removed, links/images keep their visible text, headings and list markers
 * are stripped, and whitespace is collapsed.
 */
fun speakableText(markdown: String): String {
    var text = markdown.replace(Regex("```[\\s\\S]*?```"), " ")
    text = text.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    text = text.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    text = text.replace(Regex("`([^`]*)`"), "$1")
    text = text.replace(Regex("(\\*\\*|__)(.*?)\\1"), "$2")
    text = text.replace(Regex("(\\*|_)(.*?)\\1"), "$2")
    text = text.replace(Regex("(?m)^#{1,6}\\s+"), "")
    text = text.replace(Regex("(?m)^[ \\t]*([-*+]|\\d+\\.)[ \\t]+"), "")
    text = text.replace(Regex("\\s+"), " ")
    return text.trim()
}
