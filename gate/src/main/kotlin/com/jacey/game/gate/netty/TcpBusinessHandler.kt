package com.jacey.game.gate.netty

import io.netty.channel.ChannelHandlerContext

/** TCP 业务处理：连接建立时绑定 session */
class TcpBusinessHandler : AbsBusinessHandler() {
    override fun channelActive(ctx: ChannelHandlerContext) {
        NettyServer.onChannelActive(ctx, this)
    }

    override fun getGateActorPrefix(): String {
        return "tcp-"
    }
}
