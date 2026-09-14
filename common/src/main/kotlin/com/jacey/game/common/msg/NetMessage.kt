package com.jacey.game.common.msg

import com.google.protobuf.MessageLite
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.TextFormat
import com.jacey.game.common.proto3.Rpc
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

/**
 * 网络消息（客户端 <-> Gate <-> 各服务器通用载体）
 *
 * 线协议：packetLength | msgId | errorCode | protobuf body
 */
class NetMessage() : AbstractMessage() {
    var data: ByteArray? = null
    var errorCode: Int = Rpc.RpcErrorCodeEnum.Ok_VALUE
    var sessionId: Int = 0
    var userId: Int = 0
    var userIp: String? = null

    val dataLength: Int
        get() {
            return data?.size ?: 0
        }
    val totalLength: Int
        get() {
            return 12 + dataLength
        }

    constructor(msgId: Int, lite: MessageLite) : this() {
        this.msgId = msgId
        this.data = lite.toByteArray()
    }

    constructor(msgId: Int, builder: MessageLite.Builder) : this(msgId, builder.build())

    constructor(msgId: Int, data: ByteArray?) : this() {
        this.msgId = msgId
        this.data = data
    }

    constructor(msgId: Int, errorCode: Int) : this() {
        this.msgId = msgId
        this.errorCode = errorCode
        this.data = null
    }

    override fun toByteBuf(): ByteBuf {
        val out = Unpooled.directBuffer(totalLength)
        out.writeInt(totalLength)
        out.writeInt(msgId)
        out.writeInt(errorCode)
        data?.let { out.writeBytes(it) }
        return out
    }

    /** 把 data 解析为指定 protobuf 消息对象 */
    @Suppress("UNCHECKED_CAST")
    fun <T> getProto(clz: Class<T>): T? {
        return try {
            val prototype: MessageLite

            //查看是否有缓存
            val cached = LiteCacheService.cacheClassName2MessageLiteMap[clz.name]

            // 没有缓存，则构建下
            if (cached == null) {
                val method = clz.getMethod("getDefaultInstance")
                prototype = method.invoke(null) as MessageLite
                LiteCacheService.cacheClassName2MessageLiteMap[clz.name] = prototype
            } else {
                // 有缓存，则取缓存
                prototype = cached
            }

            // 解析出来
            prototype.newBuilderForType().mergeFrom(data).buildPartial() as T
        } catch (t: Throwable) {
            KotlinLogging.logger {}.error(t) { "getLite fail: ${clz.name}" }
            null
        }
    }

    /** 便捷泛型版本 */
    inline fun <reified T> getProto(): T? {
        return getProto(T::class.java)
    }

    /** 获取 protobuf 文本（日志用） */
    fun <T> getProtobufText(clz: Class<T>): String? {
        return try {
            val lite = getProto(clz) ?: return null
            TextFormat.shortDebugString(lite as MessageOrBuilder)
        } catch (t: Throwable) {
            null
        }
    }
}
