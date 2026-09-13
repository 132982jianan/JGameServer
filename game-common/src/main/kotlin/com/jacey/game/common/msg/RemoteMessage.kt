package com.jacey.game.common.msg

import com.google.protobuf.MessageLite
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 服务器之间通讯的消息载体（GM/logic/battle/chat/gateway 之间）
 */
class RemoteMessage() : AbstractMessage() {
    var data: ByteArray? = null
    var errorCode: Int = 0

    val dataLength: Int
        get() {
            return data?.size ?: 0
        }

    constructor(rpcNum: Int, lite: MessageLite) : this() {
        this.msgId = rpcNum
        this.data = lite.toByteArray()
    }

    constructor(rpcNum: Int, builder: MessageLite.Builder) : this(rpcNum, builder.build())

    constructor(rpcNum: Int, errorCode: Int) : this() {
        this.msgId = rpcNum
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