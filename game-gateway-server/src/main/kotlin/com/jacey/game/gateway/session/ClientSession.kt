package com.jacey.game.gateway.session

import com.jacey.game.common.msg.NetMessage
import io.netty.channel.Channel
import java.net.InetSocketAddress

/**
 * 每连接会话（原 ChannelActor/ResponseActor 合并精简）
 *
 * 原设计：ChannelActor + 附属 ResponseActor 两个 actor（因为 akka classic 无法区分
 * 请求来源）；现在消息处理是协程 + 显式注册，一个 session 对象即可：
 * - 绑定 netty channel / userId / userIp
 * - write() 直接回写客户端
 *
 * Session 由 SessionManager（actor 串行）创建与索引。
 */
class ClientSession(val channel: Channel) {
    var userId: Int = 0
    val userIp: String? = (channel.remoteAddress() as? InetSocketAddress)?.address?.hostAddress

    val sessionId: Int
        get() {
            return SessionManagerService.sessionIdOf(channel) ?: 0
        }

    fun write(msg: NetMessage) {
        if (channel.isActive && channel.isWritable) {
            channel.writeAndFlush(msg)
        }
    }

    fun writeAndFlushBinary(msg: NetMessage) {
        write(msg)
    }

    fun close() {
        channel.close()
    }
}

