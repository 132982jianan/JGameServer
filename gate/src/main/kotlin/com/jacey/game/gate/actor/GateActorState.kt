package com.jacey.game.gate.actor

import com.jacey.game.common.msg.NetMessage
import io.netty.channel.Channel
import java.net.InetSocketAddress

/** 一条客户端连接的全部状态；仅由对应 GateActor 读写。 */
data class GateActorState(
    val channel: Channel,
    val sessionId: Int,
) {
    var lobbyId: Int? = null
    var accountId: String? = null
    var playerId: Int = 0

    val userIp: String?
        get() = (channel.remoteAddress() as? InetSocketAddress)?.address?.hostAddress

    fun sendClient(msg: NetMessage) {
        if (channel.isActive && channel.isWritable) channel.writeAndFlush(msg)
    }

    fun close() {
        channel.close()
    }
}
