package com.assistant.app.llm

import com.assistant.app.llm.model.ExtractedContent
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

interface ContentExtractor {
    fun extract(html: String, url: String): ExtractedContent
}

class JsoupContentExtractor(
    private val maxChars: Int = MAX_EXTRACT_CHARS,
) : ContentExtractor {

    override fun extract(html: String, url: String): ExtractedContent {
        if (html.isBlank()) return ExtractedContent(title = "", text = "")

        return try {
            val doc = Jsoup.parse(html, url)
            cleanDocument(doc)

            val title = extractTitle(doc)
            val mainElement = findMainContentElement(doc)
            val extractedText = extractText(mainElement)

            val boundedText = if (extractedText.length > maxChars) {
                extractedText.substring(0, maxChars).substringBeforeLast('\n').trim()
            } else {
                extractedText
            }

            ExtractedContent(title = title, text = boundedText)
        } catch (_: Exception) {
            ExtractedContent(title = "", text = "")
        }
    }

    private fun cleanDocument(doc: Document) {
        // Strip non-content and clutter elements
        val selectorsToRemove = listOf(
            "script", "style", "noscript", "svg", "form", "iframe", "canvas",
            "nav", "header", "footer", "aside",
            "[role='navigation']", "[role='banner']", "[role='complementary']", "[role='contentinfo']",
            "[aria-hidden='true']",
            ".cookie", ".cookies", "#cookie", "#cookies", ".cookie-banner", ".cookie-notice",
            ".advertisement", ".ad", ".ads", ".adsbygoogle", ".banner-ad",
            ".social-share", ".share-buttons", ".popup", ".modal",
        )

        for (selector in selectorsToRemove) {
            doc.select(selector).remove()
        }
    }

    private fun extractTitle(doc: Document): String {
        val metaOg = doc.select("meta[property='og:title']").attr("content").trim()
        if (metaOg.isNotBlank()) return metaOg

        val metaTwitter = doc.select("meta[name='twitter:title']").attr("content").trim()
        if (metaTwitter.isNotBlank()) return metaTwitter

        val titleTag = doc.title().trim()
        if (titleTag.isNotBlank()) return titleTag

        val h1 = doc.select("h1").first()?.text()?.trim().orEmpty()
        return h1
    }

    private fun findMainContentElement(doc: Document): Element {
        // Look for common main semantic containers
        val candidates = listOf(
            "article",
            "main",
            "[role='main']",
            "#main-content",
            "#content",
            ".post-content",
            ".article-content",
            ".entry-content",
            ".content",
        )

        for (selector in candidates) {
            val element = doc.select(selector).first()
            if (element != null && element.text().length > 100) {
                return element
            }
        }

        return doc.body() ?: doc
    }

    private fun extractText(element: Element): String {
        val sb = StringBuilder()
        val elements = element.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, td, th")

        if (elements.isEmpty()) {
            return cleanWhitespace(element.text())
        }

        for (el in elements) {
            val tag = el.tagName().lowercase()
            val text = cleanWhitespace(el.text())
            if (text.isBlank()) continue

            when {
                tag.startsWith("h") -> {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(text)
                }
                tag == "li" -> {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append("• ").append(text)
                }
                tag == "blockquote" -> {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append("> ").append(text)
                }
                else -> {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(text)
                }
            }

            if (sb.length >= maxChars * 2) break
        }

        return sb.toString().trim()
    }

    private fun cleanWhitespace(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    companion object {
        const val MAX_EXTRACT_CHARS = 1500
    }
}
