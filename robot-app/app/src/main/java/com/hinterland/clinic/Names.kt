package com.hinterland.clinic

import java.util.Locale

// place and spot names come from the backend and the robot map, never from code.
// matching is language-free: same letters/digits, or the same last word ("Doctor Blue" ~ "Dr. Blue").
object Names {

    fun key(name: String?): String =
        name.orEmpty().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun lastWord(name: String?): String? =
        name.orEmpty().lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .lastOrNull { it.isNotEmpty() }

    fun <T> match(query: String?, items: List<T>, nameOf: (T) -> String?): T? {
        val k = key(query)
        if (k.isEmpty()) return null
        items.firstOrNull { key(nameOf(it)) == k }?.let { return it }
        val last = lastWord(query) ?: return null
        // only when exactly one candidate shares the last word, otherwise it is a guess
        return items.filter { lastWord(nameOf(it)) == last }.singleOrNull()
    }

    fun same(a: String?, b: String?): Boolean =
        b != null && match(a, listOf(b)) { it } != null
}
