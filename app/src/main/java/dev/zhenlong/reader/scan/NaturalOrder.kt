package dev.zhenlong.reader.scan

import java.text.Collator
import java.util.Locale

/** Collator（中文按拼音）+ 数字段按数值比较：「第2卷」排在「第10卷」前。 */
class NaturalOrder(locale: Locale = Locale.CHINA) : Comparator<String> {
    private val collator: Collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val aDigit = a[i].isDigit()
            val bDigit = b[j].isDigit()
            val ai = chunkEnd(a, i, aDigit)
            val bj = chunkEnd(b, j, bDigit)
            val ca = a.substring(i, ai)
            val cb = b.substring(j, bj)
            val c = when {
                aDigit && bDigit -> compareNumbers(ca, cb)
                aDigit != bDigit -> if (aDigit) -1 else 1
                else -> collator.compare(ca, cb)
            }
            if (c != 0) return c
            i = ai
            j = bj
        }
        return (a.length - i) - (b.length - j)
    }

    private fun chunkEnd(s: String, start: Int, digit: Boolean): Int {
        var k = start
        while (k < s.length && s[k].isDigit() == digit) k++
        return k
    }

    private fun compareNumbers(a: String, b: String): Int {
        val x = a.map { Character.getNumericValue(it) }.dropWhile { it == 0 }
        val y = b.map { Character.getNumericValue(it) }.dropWhile { it == 0 }
        if (x.size != y.size) return x.size - y.size
        for (k in x.indices) if (x[k] != y[k]) return x[k] - y[k]
        return 0
    }
}
