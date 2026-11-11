package com.leo.connect.controller

/**
 * Bộ gõ Telex đơn giản cho bàn phím rời.
 * Nhận từ đang gõ (đã có dấu) và phím mới, trả về từ mới. Ví dụ:
 *   "tie" + 'e' -> "tiê", "tiê" + 's' -> "tiế", "tiế" + 'n' -> "tiến"
 * Quy tắc: s f r x j = sắc huyền hỏi ngã nặng, z = bỏ dấu, aa ee oo = â ê ô,
 * aw ow uw (uow) = ă ơ ư (ươ), w đứng riêng = ư, dd = đ. Gõ lặp phím dấu để bỏ (vd "ass" -> "as").
 * Dấu đặt theo kiểu cũ: hòa, thủy, mía; có phụ âm cuối thì dấu ở nguyên âm cuối (hoàng).
 */
object Telex {
    private val TABLE = mapOf(
        'a' to "aáàảãạ", 'ă' to "ăắằẳẵặ", 'â' to "âấầẩẫậ",
        'e' to "eéèẻẽẹ", 'ê' to "êếềểễệ",
        'i' to "iíìỉĩị",
        'o' to "oóòỏõọ", 'ô' to "ôốồổỗộ", 'ơ' to "ơớờởỡợ",
        'u' to "uúùủũụ", 'ư' to "ưứừửữự",
        'y' to "yýỳỷỹỵ",
    )
    private val REVERSE: Map<Char, Pair<Char, Int>> = HashMap<Char, Pair<Char, Int>>().apply {
        for ((base, forms) in TABLE) forms.forEachIndexed { tone, c -> put(c, base to tone) }
    }
    private const val TONE_KEYS = "sfrxj"
    private const val SPECIAL = "ăâêôơư"

    private class Letter(var base: Char, val upper: Boolean)

    private fun isVowel(c: Char) = TABLE.containsKey(c)

    private fun decompose(word: String): Pair<MutableList<Letter>, Int> {
        var tone = 0
        val letters = ArrayList<Letter>(word.length + 1)
        for (ch in word) {
            val lower = ch.lowercaseChar()
            val upper = ch != lower
            val r = REVERSE[lower]
            if (r != null) {
                if (r.second != 0) tone = r.second
                letters.add(Letter(r.first, upper))
            } else {
                letters.add(Letter(lower, upper))
            }
        }
        return letters to tone
    }

    /** Vị trí nguyên âm mang dấu, -1 nếu không có nguyên âm. */
    private fun tonePosition(letters: List<Letter>): Int {
        val b = letters.map { it.base }
        val vowels = ArrayList<Int>()
        for (i in b.indices) {
            if (!isVowel(b[i])) continue
            // "qu", "gi" + nguyên âm: u/i thuộc phụ âm đầu
            if (b[i] == 'u' && i > 0 && b[i - 1] == 'q') continue
            if (b[i] == 'i' && i > 0 && b[i - 1] == 'g' && i + 1 < b.size && isVowel(b[i + 1])) continue
            vowels.add(i)
        }
        if (vowels.isEmpty()) return -1
        val specials = vowels.filter { SPECIAL.indexOf(b[it]) >= 0 }
        if (specials.isNotEmpty()) return specials.last()
        if (vowels.size == 1) return vowels[0]
        val hasFinalConsonant = vowels.last() < b.size - 1
        if (hasFinalConsonant) return vowels.last()
        if (vowels.size >= 3) return vowels[1]
        return vowels[0]
    }

    private fun compose(letters: List<Letter>, tone: Int): String {
        val pos = if (tone > 0) tonePosition(letters) else -1
        val sb = StringBuilder(letters.size)
        letters.forEachIndexed { i, l ->
            var c = if (i == pos) TABLE[l.base]?.get(tone) ?: l.base else l.base
            if (l.upper) c = c.uppercaseChar()
            sb.append(c)
        }
        return sb.toString()
    }

    private fun hat(c: Char) = when (c) { 'a' -> 'â'; 'e' -> 'ê'; 'o' -> 'ô'; else -> c }

    fun process(word: String, key: Char): String {
        if (!key.isLetter()) return word + key
        val k = key.lowercaseChar()
        val keyUpper = key != k
        val (letters, tone) = decompose(word)
        val hasVowel = letters.any { isVowel(it.base) }

        // Dấu thanh
        val toneIndex = TONE_KEYS.indexOf(k) + 1
        if (toneIndex > 0 && hasVowel && tonePosition(letters) >= 0) {
            return if (toneIndex == tone) compose(letters, 0) + key else compose(letters, toneIndex)
        }
        if (k == 'z' && tone != 0) return compose(letters, 0)

        // dd -> đ
        if (k == 'd') {
            val idx = letters.indexOfFirst { it.base == 'd' || it.base == 'đ' }
            if (idx >= 0) {
                if (letters[idx].base == 'd') {
                    letters[idx].base = 'đ'
                    return compose(letters, tone)
                }
                letters[idx].base = 'd'
                letters.add(Letter('d', keyUpper))
                return compose(letters, tone)
            }
        }

        // aa ee oo -> â ê ô
        if (k == 'a' || k == 'e' || k == 'o') {
            val target = hat(k)
            val last = letters.indexOfLast { it.base == k || it.base == target || (k == 'a' && it.base == 'ă') || (k == 'o' && it.base == 'ơ') }
            if (last >= 0) {
                val cur = letters[last].base
                if (cur == target) {
                    letters[last].base = k
                    letters.add(Letter(k, keyUpper))
                } else {
                    letters[last].base = target
                }
                return compose(letters, tone)
            }
        }

        // w: ă ơ ư, uo -> ươ
        if (k == 'w') {
            val b = letters.map { it.base }
            for (i in 0 until b.size - 1) {
                if (b[i] == 'u' && b[i + 1] == 'o') {
                    letters[i].base = 'ư'; letters[i + 1].base = 'ơ'
                    return compose(letters, tone)
                }
                if (b[i] == 'ư' && b[i + 1] == 'ơ') {
                    letters[i].base = 'u'; letters[i + 1].base = 'o'
                    letters.add(Letter('w', keyUpper))
                    return compose(letters, tone)
                }
            }
            val idx = letters.indexOfLast { it.base == 'a' || it.base == 'o' || it.base == 'u' || it.base == 'ă' || it.base == 'ơ' || it.base == 'ư' }
            if (idx >= 0) {
                when (letters[idx].base) {
                    'a' -> letters[idx].base = 'ă'
                    'o' -> letters[idx].base = 'ơ'
                    'u' -> letters[idx].base = 'ư'
                    'ă' -> { letters[idx].base = 'a'; letters.add(Letter('w', keyUpper)) }
                    'ơ' -> { letters[idx].base = 'o'; letters.add(Letter('w', keyUpper)) }
                    'ư' -> { letters[idx].base = 'u'; letters.add(Letter('w', keyUpper)) }
                }
                return compose(letters, tone)
            }
            if (!hasVowel) {
                letters.add(Letter('ư', keyUpper))
                return compose(letters, tone)
            }
        }

        letters.add(Letter(k, keyUpper))
        return compose(letters, tone)
    }

    /** Gõ cả chuỗi phím (dùng cho kiểm thử). */
    fun type(keys: String): String {
        var out = ""
        var word = ""
        for (ch in keys) {
            if (ch.isLetter()) {
                word = process(word, ch)
            } else {
                out += word + ch
                word = ""
            }
        }
        return out + word
    }
}
