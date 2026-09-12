package com.jacey.game.common.util

/** 字符串工具 */
object StringUtil {
    fun isNullOrEmpty(str: String?): Boolean = str == null || str.isEmpty()
    fun isDigitChar(c: Char): Boolean = c in '0'..'9'
    fun isLetterChar(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z'
    fun isBaseChineseChar(c: Char): Boolean = c in '\u4E00'..'\u9FA5'

    fun <T> getCollectionMemberString(collection: Collection<T>?, splitString: String = ","): String {
        if (collection.isNullOrEmpty()) return ""
        val split = if (splitString.isEmpty()) "," else splitString
        return collection.joinToString(split) { it.toString() }
    }
}
