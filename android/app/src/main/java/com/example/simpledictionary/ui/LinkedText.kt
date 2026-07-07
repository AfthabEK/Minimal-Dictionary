package com.example.simpledictionary.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

private val WORD_REGEX = Regex("[\\p{L}\\p{Nd}][\\p{L}\\p{Nd}'’-]*")

/**
 * Makes every word of a definition tappable. Stored [links] (multi-word
 * targets, display ≠ target) are matched first and underlined; every
 * remaining word simply targets its own text.
 */
fun buildLinkedText(
    text: String,
    links: List<Pair<String, String>>,
    onWordTap: (String) -> Unit,
): AnnotatedString {
    data class Span(val start: Int, val end: Int, val target: String, val underline: Boolean)

    val spans = mutableListOf<Span>()
    val taken = BooleanArray(text.length)

    fun claim(start: Int, end: Int, target: String, underline: Boolean) {
        if (start >= end || (start until end).any { taken[it] }) return
        for (i in start until end) taken[i] = true
        spans += Span(start, end, target, underline)
    }

    for ((display, target) in links) {
        var idx = text.indexOf(display)
        if (idx < 0) idx = text.lowercase().indexOf(display.lowercase())
        if (idx >= 0) claim(idx, idx + display.length, target, underline = true)
    }
    for (m in WORD_REGEX.findAll(text)) {
        claim(m.range.first, m.range.last + 1, m.value, underline = false)
    }

    return buildAnnotatedString {
        append(text)
        for (s in spans) {
            addLink(
                LinkAnnotation.Clickable(
                    tag = s.target,
                    // Explicit styles both ways: with null, the theme's
                    // default link styling underlines every word.
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            textDecoration = if (s.underline) {
                                TextDecoration.Underline
                            } else {
                                TextDecoration.None
                            },
                        ),
                    ),
                    linkInteractionListener = { onWordTap(s.target) },
                ),
                s.start,
                s.end,
            )
        }
    }
}
