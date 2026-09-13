package com.jacey.game.gateway.netty

import io.netty.channel.ChannelHandlerContext

/** TCP 业务处理：连接建立时绑定 session */
class TcpBusinessHandler : AbsBusinessHandler() {
    override fun channelActive(ctx: ChannelHandlerContext) {
        NettyServer.onChannelActive(ctx)
    }

    override fun getClientSessionActorPrefix(): String {
        return "tcp-"
    }
}
