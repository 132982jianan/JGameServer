package com.jacey.game.gui.netty

import com.jacey.game.common.msg.NetMessage
import com.jacey.game.gui.service.MessageRouterService
import com.jacey.game.gui.service.NetService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.gui.service.SessionService
import java.util.concurrent.CompletableFuture

class ClientHandler : ChannelInboundHandlerAdapter() {
    private val logger = KotlinLogging.logger {}
    val handshakeComplete = CompletableFuture<Unit>()

    override fun channelActive(ctx: ChannelHandlerContext) {
        ctx.fireChannelActive()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        logger.warn { "与服务器已断开......" }
        handshakeComplete.completeExceptionally(IllegalStateException("Gate disconnected before WebSocket handshake completed"))
        NetService.onDisconnected(ctx.channel())
        ctx.fireChannelInactive()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is NetMessage) {
            MessageRouterService.dispatch(msg)
        } else {
            ctx.fireChannelRead(msg)
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            handshakeComplete.complete(Unit)
        } else if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT) {
            handshakeComplete.completeExceptionally(IllegalStateException("WebSocket handshake timed out"))
            ctx.close()
        } else if (evt is IdleStateEvent && evt.state() == IdleState.WRITER_IDLE) {
            if (SessionService.isLogin) {
                val request = CommonMsg.HeartbeatRequest.newBuilder()
                    .setClientTimestamp(System.currentTimeMillis())
                ctx.writeAndFlush(NetMessage(Rpc.RpcNameEnum.Heartbeat_VALUE, request))
                logger.info { "【心跳】发送心跳包结束...." }
            }
        } else {
            ctx.fireUserEventTriggered(evt)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        handshakeComplete.completeExceptionally(cause)
        ctx.close()
        val msg = cause.message ?: ""
        if (!msg.contains("远程主机强迫关闭")) {
            logger.error(cause) { "【链接异常断开】" }
        }
    }
}
