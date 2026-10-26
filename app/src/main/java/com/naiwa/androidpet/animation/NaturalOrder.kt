package com.naiwa.androidpet.animation

import java.util.Locale

/** Numeric runs are compared by significant length, without parsing into a bounded integer. */
object NaturalOrder : Comparator<String> {
    private val tokens = Regex("[0-9]+|[^0-9]+")
    override fun compare(a: String, b: String): Int {
        val left = tokens.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val right = tokens.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]; val y = right[i]
            val result = if (x.first() in '0'..'9' && y.first() in '0'..'9') {
                val nx = x.trimStart('0').ifEmpty { "0" }
                val ny = y.trimStart('0').ifEmpty { "0" }
                val numeric = nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
                numeric.takeIf { it != 0 } ?: x.length.compareTo(y.length)
            } else x.compareTo(y)
            if (result != 0) return result
        }
        return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
