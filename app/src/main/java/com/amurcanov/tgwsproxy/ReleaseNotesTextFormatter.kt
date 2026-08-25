package com.amurcanov.tgwsproxy

data class ReleaseNotesPreview(
    val text: String,
    val truncated: Boolean,
)

object ReleaseNotesTextFormatter {
    private val headingPrefix = Regex("^\\s{0,3}#{1,6}\\s+")
    private val quotePrefix = Regex("^\\s*>\\s?")
    private val bulletPrefix = Regex("^\\s*[-*+]\\s+")
    private val markdownLink = Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)")
    private val inlineCode = Regex("`+([^`]+)`+")
    private val excessiveBlankLines = Regex("\\n{3,}")

    fun toPlainText(markdown: String): String {
        if (markdown.isBlank()) return ""

        var insideFence = false
        val cleaned = buildList {
            markdown
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lineSequence()
                .forEach { rawLine ->
                    val trimmedStart = rawLine.trimStart()
                    if (trimmedStart.startsWith("```")) {
                        insideFence = !insideFence
                        return@forEach
                    }

                    var line = rawLine.trimEnd()
                    if (!insideFence) {
                        line = line.replace(headingPrefix, "")
                        line = line.replace(quotePrefix, "")
                        line = line.replace(bulletPrefix, "• ")
                        line = line.replace(markdownLink, "$1")
                        line = line.replace(inlineCode, "$1")
                        line = line
                            .replace("**", "")
                            .replace("__", "")
                            .replace("~~", "")
                    }
                    add(line)
                }
        }.joinToString("\n")

        return cleaned
            .replace(excessiveBlankLines, "\n\n")
            .trim()
    }

    fun preview(
        markdown: String,
        maxLines: Int = 5,
        maxChars: Int = 420,
    ): ReleaseNotesPreview {
        val plain = toPlainText(markdown)
        if (plain.isEmpty()) return ReleaseNotesPreview("", false)

        val allLines = plain.lines()
        var truncated = allLines.size > maxLines
        var text = allLines.take(maxLines).joinToString("\n").trimEnd()

        if (text.length > maxChars) {
            truncated = true
            val candidate = text.take(maxChars).trimEnd()
            val lastWhitespace = candidate.indexOfLast { it.isWhitespace() }
            text = if (lastWhitespace >= maxChars / 2) {
                candidate.take(lastWhitespace).trimEnd()
            } else {
                candidate
            }
        }

        if (truncated) {
            text = text.trimEnd().trimEnd('…') + "…"
        }
        return ReleaseNotesPreview(text, truncated)
    }
}
