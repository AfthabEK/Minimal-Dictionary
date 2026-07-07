package com.example.simpledictionary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.simpledictionary.data.DictionaryDb
import com.example.simpledictionary.data.Lookup
import com.example.simpledictionary.data.Redirect
import com.example.simpledictionary.data.Sense
import com.example.simpledictionary.data.displayPos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun EntryScreen(word: String, onWordTap: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lookup by produceState<Lookup?>(initialValue = null, word) {
        value = withContext(Dispatchers.IO) { DictionaryDb.get(context).lookup(word) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        val result = lookup ?: return@Column
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 28.dp, end = 28.dp, top = 24.dp, bottom = 48.dp,
            ),
        ) {
            entryContent(result, onWordTap)
        }
    }
}

private fun LazyListScope.entryContent(result: Lookup, onWordTap: (String) -> Unit) {
    // Case-insensitive lookup can hit several headwords ("Polish"/"polish");
    // render each under its own big headword.
    val byHeadword = result.entries.groupBy { it.word }
    val headwords = byHeadword.keys.toList()

    if (result.isMiss) {
        item { Headword(result.input, ipa = null) }
        item {
            Text(
                "No entry found.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 17.sp,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        return
    }

    if (headwords.isEmpty()) {
        item { Headword(result.input, ipa = null) }
    }

    headwords.forEachIndexed { hwIndex, headword ->
        val entries = byHeadword.getValue(headword)
        val ipa = entries.firstNotNullOfOrNull { it.pronunciations.firstOrNull() }?.ipa

        item(key = "hw-$headword") {
            Headword(headword, ipa, topPadding = if (hwIndex == 0) 0.dp else 40.dp)
        }

        // Merge entries of the same POS (separate etymologies) into one
        // section, in first-appearance order.
        val byPos = LinkedHashMap<String, MutableList<Sense>>()
        for (entry in entries) {
            byPos.getOrPut(entry.pos) { mutableListOf() } += entry.senses
        }

        byPos.forEach { (pos, senses) ->
            item(key = "pos-$headword-$pos") {
                Text(
                    displayPos(pos),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 26.dp, bottom = 2.dp),
                )
            }
            senses.forEachIndexed { i, sense ->
                item(key = "sense-$headword-$pos-$i") {
                    SenseRow(label = "${i + 1}.", sense = sense, onWordTap = onWordTap)
                }
            }
        }
    }

    val redirects = result.redirects.distinct()
    if (redirects.isNotEmpty()) {
        item(key = "redirects") {
            Column(modifier = Modifier.padding(top = if (headwords.isEmpty()) 24.dp else 32.dp)) {
                redirects.forEach { RedirectRow(it, onWordTap) }
            }
        }
    }
}

@Composable
private fun Headword(word: String, ipa: String?, topPadding: Dp = 0.dp) {
    Column(modifier = Modifier.padding(top = topPadding)) {
        Text(
            word,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 44.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 50.sp,
        )
        if (ipa != null) {
            Text(
                ipa,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 19.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SenseRow(label: String, sense: Sense, onWordTap: (String) -> Unit, indent: Dp = 0.dp) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(start = indent, top = 10.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            modifier = Modifier.width(28.dp),
        )
        Column {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            val body = buildAnnotatedString {
                if (sense.tags.isNotEmpty()) {
                    withStyle(SpanStyle(color = muted, fontStyle = FontStyle.Italic)) {
                        append("(${sense.tags.joinToString(", ")}) ")
                    }
                }
                append(buildLinkedText(sense.definition, sense.links, onWordTap))
            }
            Text(
                body,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 17.sp,
                lineHeight = 24.sp,
            )
            sense.examples.firstOrNull()?.let { example ->
                Text(
                    example,
                    color = muted,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            sense.subsenses.forEachIndexed { i, sub ->
                SenseRow(
                    label = "${('a' + i)}.",
                    sense = sub,
                    onWordTap = onWordTap,
                    indent = 4.dp,
                )
            }
        }
    }
}

@Composable
private fun RedirectRow(redirect: Redirect, onWordTap: (String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val fg = MaterialTheme.colorScheme.onBackground
    val label = redirect.label.ifEmpty { "form of ${redirect.target}" }
    val targetStart = label.indexOf(redirect.target)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = muted, fontStyle = FontStyle.Italic)) { append(label) }
        if (targetStart >= 0) {
            addStyle(
                SpanStyle(
                    color = fg,
                    fontStyle = FontStyle.Normal,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                ),
                targetStart,
                targetStart + redirect.target.length,
            )
        } else {
            withStyle(SpanStyle(color = muted, fontStyle = FontStyle.Italic)) { append(" → ") }
            withStyle(
                SpanStyle(color = fg, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline),
            ) { append(redirect.target) }
        }
    }
    Text(
        text,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onWordTap(redirect.target) }
            .padding(vertical = 8.dp),
    )
}
