package com.ppailab.agnesstudio.model

/** Builds a genuinely shorter string for a collapsed text branch. */
object CollapsibleTextFormatter {
    fun isDeterministicallyCollapsible(
        content: String,
        maxCharacters: Int,
        maxLines: Int,
    ): Boolean =
        content.length > maxCharacters || content.count { it == '\n' } >= maxLines

    fun collapsedText(content: String, maxCharacters: Int, maxLines: Int): String {
        require(maxCharacters > 0 && maxLines > 0)

        var lineEnd = content.length
        var newlineCount = 0
        for (index in content.indices) {
            if (content[index] == '\n' && ++newlineCount == maxLines) {
                lineEnd = index
                break
            }
        }

        var end = minOf(content.length, maxCharacters, lineEnd)
        if (end == content.length) return content

        if (
            end < content.length &&
            end > 0 &&
            Character.isHighSurrogate(content[end - 1]) &&
            Character.isLowSurrogate(content[end])
        ) {
            end -= 1
        }
        return content.substring(0, end).trimEnd() + "…"
    }
}
