package com.jacey.game.common.serialize

import akka.serialization.Serializer
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import scala.Option
import java.nio.ByteBuffer

/**
 * Akka remote 自定义序列化器（artery 传输 NetMessage/RemoteMessage）
 *
 * NetMessage 编解码规则：
 * rpcNum(4) errorCode(4) sessionId(4) userId(4) userIpLen(4) [userIp] [data]
 *
 * RemoteMessage 编解码规则：
 * rpcNum(4) errorCode(4) [data]
 */
class NetMessageSerializer : Serializer {
    override fun identifier(): Int = 2001
    override fun includeManifest(): Boolean = false

    override fun toBinary(o: Any): ByteArray {
        val msg = o as NetMessage
        val ipBytes = msg.userIp?.toByteArray(Charsets.UTF_8)
        val ipLen = ipBytes?.size ?: 0
        val total = 20 + ipLen + msg.dataLength
        val bb = ByteBuffer.allocate(total)
        bb.putInt(msg.rpcNum)
        bb.putInt(msg.errorCode)
        bb.putInt(msg.sessionId)
        bb.putInt(msg.userId)
        bb.putInt(ipLen)
        ipBytes?.let { bb.put(it) }
        msg.data?.let { bb.put(it) }
        return bb.array()
    }

    override fun fromBinary(bytes: ByteArray): Any {
        val msg = NetMessage()
        val bb = ByteBuffer.wrap(bytes)
        msg.rpcNum = bb.int
        msg.errorCode = bb.int
        msg.sessionId = bb.int
        msg.userId = bb.int
        val ipLen = bb.int
        if (ipLen > 0) {
            val ipBytes = ByteArray(ipLen)
            bb.get(ipBytes)
            msg.userIp = String(ipBytes, Charsets.UTF_8)
        }
        val data = ByteArray(bytes.size - ipLen - 20)
        bb.get(data)
        msg.data = data
        return msg
    }

    override fun fromBinary(bytes: ByteArray, manifest: Option<Class<*>>): Any = fromBinary(bytes)
}

class RemoteMessageSerializer : Serializer {
    override fun identifier(): Int = 2002
    override fun includeManifest(): Boolean = false

    override fun toBinary(o: Any): ByteArray {
        val msg = o as RemoteMessage
        val bb = ByteBuffer.allocate(8 + msg.dataLength)
        bb.putInt(msg.rpcNum)
        bb.putInt(msg.errorCode)
        msg.data?.let { bb.put(it) }
        return bb.array()
    }

    override fun fromBinary(bytes: ByteArray): Any {
        val msg = RemoteMessage()
        val bb = ByteBuffer.wrap(bytes)
        msg.rpcNum = bb.int
        msg.errorCode = bb.int
        val data = ByteArray(bytes.size - 8)
        bb.get(data)
        msg.data = data
        return msg
    }

    override fun fromBinary(bytes: ByteArray, manifest: Option<Class<*>>): Any = fromBinary(bytes)
}
