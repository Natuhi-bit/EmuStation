package com.emustation.emusync.data.storage

/**
 * Python の `fnmatch.fnmatchcase` 相当のグロブ照合（設計書 §4.1）。
 *
 * - `*` は `/` を含む任意の文字列、`?` は任意の1文字
 * - `[...]` は文字クラス。`[!...]` は否定。閉じ `]` がなければ `[` はただの文字
 * - 大文字と小文字は区別する
 *
 * 文字クラスの細部（先頭の `]`、範囲 `a-z`、`-` の扱い、空の範囲）も Python 3 の `fnmatch.translate` に合わせる。
 * 文字はコードポイント単位で扱う。
 */
class GlobPattern(val pattern: String) {
    private sealed interface Token
    private data object Star : Token
    private data object Any1 : Token
    private data class Literal(val cp: Int) : Token
    private class CharClass(val negated: Boolean, val ranges: List<IntRange>) : Token {
        fun matches(cp: Int): Boolean = ranges.any { cp in it } != negated
    }

    private val tokens: List<Token> = compile(pattern.codePoints().toArray())

    fun matches(name: String): Boolean {
        val s = name.codePoints().toArray()
        // 1文字ずつのトークンと * だけなので、最後の * まで戻る方式で照合できる
        var si = 0
        var ti = 0
        var starTi = -1
        var starSi = 0
        while (si < s.size) {
            val tok = tokens.getOrNull(ti)
            when {
                tok == Star -> {
                    starTi = ti++
                    starSi = si
                }
                tok != null && matchOne(tok, s[si]) -> {
                    si++
                    ti++
                }
                starTi >= 0 -> {
                    ti = starTi + 1
                    si = ++starSi
                }
                else -> return false
            }
        }
        while (tokens.getOrNull(ti) == Star) ti++
        return ti == tokens.size
    }

    private fun matchOne(tok: Token, cp: Int): Boolean = when (tok) {
        Any1 -> true
        is Literal -> tok.cp == cp
        is CharClass -> tok.matches(cp)
        Star -> false
    }

    private companion object {
        val STAR = '*'.code
        val QUESTION = '?'.code
        val OPEN = '['.code
        val CLOSE = ']'.code
        val BANG = '!'.code
        val HYPHEN = '-'.code

        fun compile(p: IntArray): List<Token> {
            val out = mutableListOf<Token>()
            var i = 0
            val n = p.size
            while (i < n) {
                val c = p[i++]
                when (c) {
                    STAR -> if (out.lastOrNull() != Star) out += Star
                    QUESTION -> out += Any1
                    OPEN -> {
                        var j = i
                        if (j < n && p[j] == BANG) j++
                        if (j < n && p[j] == CLOSE) j++
                        while (j < n && p[j] != CLOSE) j++
                        if (j >= n) {
                            out += Literal(OPEN)
                        } else {
                            out += charClass(p.copyOfRange(i, j))
                            i = j + 1
                        }
                    }
                    else -> out += Literal(c)
                }
            }
            return out
        }

        /**
         * `[` と `]` の間を文字クラスにする。
         * 範囲 `a-b` の右端の直後の `-` は範囲の記号にならない。先頭・末尾の `-` はただの文字。
         * 左端が右端より大きい範囲は空（何にも一致しない）。
         */
        fun charClass(body: IntArray): CharClass {
            var k = 0
            val negated = body.isNotEmpty() && body[0] == BANG
            if (negated) k = 1
            val ranges = mutableListOf<IntRange>()
            while (k < body.size) {
                val c = body[k]
                if (k + 2 < body.size && body[k + 1] == HYPHEN) {
                    ranges += c..body[k + 2] // 左端 > 右端なら空の範囲
                    k += 3
                } else {
                    ranges += c..c
                    k += 1
                }
            }
            return CharClass(negated, ranges)
        }
    }
}

/** 除外の判定：パターンごとに「相対パス全体」と「ファイル名」の両方に照合する */
class ExcludeMatcher(patterns: List<String>) {
    private val globs = patterns.filter { it.isNotEmpty() }.map(::GlobPattern)

    fun isExcluded(rel: String): Boolean {
        val fileName = rel.substringAfterLast('/')
        return globs.any { it.matches(rel) || it.matches(fileName) }
    }
}
