package com.example.sensevoicefuto

/**
 * Conservative, local-only cleanup. It deliberately avoids semantic rewriting.
 * The goal is to remove obvious filler and immediate stutters without changing meaning.
 */
object TextCleaner {
    private val fillers = Regex("(?:(?<=^)|(?<=[，。！？,.!?\\s]))(?:嗯+|呃+|额+|呣+|唔+)(?=$|[，。！？,.!?\\s])")
    private val repeatedChineseChunk = Regex("([\\p{IsHan}]{1,6})(?:[，、,\\s]*\\1){1,3}")
    private val repeatedLatinWord = Regex("(?i)\\b([a-z][a-z'-]{1,20})(?:\\s+\\1){1,3}\\b")
    private val repeatedPunctuation = Regex("([，。！？,.!?])\\1+")
    private val spacesBeforePunctuation = Regex("\\s+([，。！？,.!?])")
    private val manySpaces = Regex("[ \\t]{2,}")

    fun clean(input: String): String {
        var s = input.trim()
        s = fillers.replace(s, "")
        s = repeatedChineseChunk.replace(s) { it.groupValues[1] }
        s = repeatedLatinWord.replace(s) { it.groupValues[1] }
        s = repeatedPunctuation.replace(s) { it.groupValues[1] }
        s = spacesBeforePunctuation.replace(s, "$1")
        s = manySpaces.replace(s, " ")
        s = s.replace(Regex("^[，、,\\s]+"), "")
        return s.trim()
    }
}
