package com.jacey.game.common.util

import java.security.MessageDigest

/** MD5 工具（线程安全：每次 new digest 实例） */
object MD5Util {
    private val hexDigits = "0123456789abcdef".toCharArray()

    fun md5(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        val bb = digest.digest(data)
        val sb = StringBuilder(bb.size * 2)
        for (b in bb) {
            sb.append(hexDigits[b.toInt() ushr 4 and 0xf])
            sb.append(hexDigits[b.toInt() and 0xf])
        }
        return sb.toString()
    }

    fun md5(str: String): String = md5(str.toByteArray(Charsets.UTF_8))
}
