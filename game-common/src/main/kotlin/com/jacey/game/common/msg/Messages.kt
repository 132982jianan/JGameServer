package com.jacey.game.common.msg

import com.jacey.game.common.proto3.Rpc
import com.jacey.game.common.util.DateTimeUtil
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

/**
 * 消息载体接口：协议号 + 二进制化
 */
interface IMessage {
    val rpcNum: Int
    fun toBinaryMsg(): ByteBuf?
}

/** 抽象基类：公共 rpcNum 字段 */
abstract class AbstractMessage : IMessage {
    override var rpcNum: Int = 0
    /** 附加对象（本地消息可携带 protobuf Builder/对象，远程消息走 data） */
    var lite: Any? = null
    override fun toBinaryMsg(): ByteBuf? = null
}

/**
 * 网络消息（客户端 <-> gateway <-> 各服务器 通用载体）
 *
 * 线协议：packetLength | rpcNum | errorCode | protobuf body
 */
class NetMessage() : AbstractMessage() {
    var data: ByteArray? = null
    var errorCode: Int = Rpc.RpcErrorCodeEnum.Ok_VALUE
    var sessionId: Int = 0
    var userId: Int = 0
    var userIp: String? = null

    constructor(rpcNum: Int, lite: com.google.protobuf.MessageLite) : this() {
        this.rpcNum = rpcNum
        this.data = lite.toByteArray()
    }

    constructor(rpcNum: Int, builder: com.google.protobuf.MessageLite.Builder) : this(rpcNum, builder.build())

    constructor(rpcNum: Int, data: ByteArray?) : this() {
        this.rpcNum = rpcNum
        this.data = data
    }

    constructor(rpcNum: Int, errorCode: Int) : this() {
        this.rpcNum = rpcNum
        this.errorCode = errorCode
        this.data = null
    }

    val dataLength: Int get() = data?.size ?: 0
    val totalLength: Int get() = 12 + dataLength

    override fun toBinaryMsg(): ByteBuf {
        val out = Unpooled.directBuffer(totalLength)
        out.writeInt(totalLength)
        out.writeInt(rpcNum)
        out.writeInt(errorCode)
        data?.let { out.writeBytes(it) }
        return out
    }

    /** 反射缓存：protobuf Lite 默认实例 */
    private object LiteCache {
        val cache = HashMap<String, com.google.protobuf.MessageLite>()
    }

    /** 把 data 解析为指定 protobuf 消息对象 */
    @Suppress("UNCHECKED_CAST")
    fun <T> getProto(clz: Class<T>): T? {
        return try {
            val prototype: com.google.protobuf.MessageLite
            val cached = LiteCache.cache[clz.name]
            if (cached == null) {
                val method = clz.getMethod("getDefaultInstance")
                prototype = method.invoke(null) as com.google.protobuf.MessageLite
                LiteCache.cache[clz.name] = prototype
            } else {
                prototype = cached
            }
            prototype.newBuilderForType().mergeFrom(data).buildPartial() as T
        } catch (t: Throwable) {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}.error(t) { "getLite fail: ${clz.name}" }
            null
        }
    }

    /** 便捷泛型版本 */
    inline fun <reified T> getProto(): T? = getProto(T::class.java)

    /** 获取 protobuf 文本（日志用） */
    fun <T> getProtobufText(clz: Class<T>): String? {
        return try {
            val lite = getProto(clz) ?: return null
            com.google.protobuf.TextFormat.shortDebugString(lite as com.google.protobuf.MessageOrBuilder)
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * 服务器之间通讯的消息载体（GM/logic/battle/chat/gateway 之间）
 */
class RemoteMessage() : AbstractMessage() {
    var data: ByteArray? = null
    var errorCode: Int = 0

    constructor(rpcNum: Int, lite: com.google.protobuf.MessageLite) : this() {
        this.rpcNum = rpcNum
        this.data = lite.toByteArray()
    }

    constructor(rpcNum: Int, builder: com.google.protobuf.MessageLite.Builder) : this(rpcNum, builder.build())

    constructor(rpcNum: Int, errorCode: Int) : this() {
        this.rpcNum = rpcNum
        this.errorCode = errorCode
    }

    val dataLength: Int get() = data?.size ?: 0

    /** 把 data 解析为指定 protobuf 消息对象 */
    @Suppress("UNCHECKED_CAST")
    fun <T> getProto(clz: Class<T>): T? {
        return try {
            val method = clz.getMethod("getDefaultInstance")
            val prototype = method.invoke(null) as com.google.protobuf.MessageLite
            prototype.newBuilderForType().mergeFrom(data).buildPartial() as T
        } catch (t: Throwable) {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}.error(t) { "getLite fail: ${clz.name}" }
            null
        }
    }

    inline fun <reified T> getProto(): T? = getProto(T::class.java)
}

/** 内部服务器通讯实体（本节点内 self 调度/定时任务用，不跨节点） */
class LocalMessage(rpcNum: Int, lite: Any? = null) : AbstractMessage() {
    init {
        this.rpcNum = rpcNum
        this.lite = lite
    }
}
