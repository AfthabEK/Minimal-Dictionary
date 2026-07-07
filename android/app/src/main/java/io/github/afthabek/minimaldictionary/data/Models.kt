package io.github.afthabek.minimaldictionary.data

data class Pronunciation(
    val ipa: String,
    val accents: List<String>,
)

data class Sense(
    val definition: String,
    val tags: List<String>,
    val examples: List<String>,
    val formOf: List<String>,
    val links: List<Pair<String, String>>, // display text -> target headword
    val subsenses: List<Sense>,
)

data class Entry(
    val word: String, // exact-case headword this entry belongs to
    val pos: String,
    val senses: List<Sense>,
    val pronunciations: List<Pronunciation>,
)

data class Redirect(
    val target: String,
    val label: String, // '' = plain inflection, render "form of <target>"
)

data class Lookup(
    val input: String,
    val entries: List<Entry>,
    val redirects: List<Redirect>,
) {
    val isMiss: Boolean get() = entries.isEmpty() && redirects.isEmpty()
}

private val POS_DISPLAY = mapOf(
    "adj" to "adjective",
    "adv" to "adverb",
    "intj" to "interjection",
    "pron" to "pronoun",
    "prep_phrase" to "prepositional phrase",
    "name" to "proper noun",
)

fun displayPos(pos: String): String = POS_DISPLAY[pos] ?: pos
