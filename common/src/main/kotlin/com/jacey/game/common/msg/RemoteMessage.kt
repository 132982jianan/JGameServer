package com.jacey.game.common.msg

import com.google.protobuf.MessageLite
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 服务器之间通讯的消息载体（Gate/Lobby/Global/Battle 之间）
 */
class RemoteMessage() : AbstractMessage() {
    //消息体
    var data: ByteArray? = null

    var errorCode: Int = 0

    val dataLength: Int
        get() {
            return data?.size ?: 0
        }

    constructor(msgId: Int, lite: MessageLite) : this() {
        this.msgId = msgId
        this.data = lite.toByteArray()
    }

    constructor(msgId: Int, builder: MessageLite.Builder) : this(msgId, builder.build())

    constructor(msgId: Int, errorCode: Int) : this() {
        this.msgId = msgId
        this.errorCode = errorCode
    }

    /** 把 data 解析为指定 protobuf 消息对象 */
    @Suppress("UNCHECKED_CAST")
    fun <T> getProto(clz: Class<T>): T? {
        return try {
            val method = clz.getMethod("getDefaultInstance")
            val prototype = method.invoke(null) as MessageLite
            prototype.newBuilderForType().mergeFrom(data).buildPartial() as T
        } catch (t: Throwable) {
            KotlinLogging.logger {}.error(t) { "getLite fail: ${clz.name}" }
            null
        }
    }

    inline fun <reified T> getProto(): T? {
        return getProto(T::class.java)
    }
}
