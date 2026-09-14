package com.jacey.game.gate.netty

import akka.actor.ActorRef
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.gate.actor.GateClientMsg
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent

/** WebSocket 会话生命周期与业务入站处理；帧编解码由 WebSocketNetMessageCodec 负责。 */
class WebSocketBusinessHandler : ChannelInboundHandlerAdapter() {
    private val logger = KotlinLogging.logger {}

    override fun channelInactive(ctx: ChannelHandlerContext) {
        ctx.channel().attr(NettyServer.GATE_ACTOR_KEY).getAndSet(null)
            ?.tell(LocalMessage(InternalMessageId.GATE_DISCONNECTED), ActorRef.noSender())
        ctx.fireChannelInactive()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is NetMessage) {
            val gateActor = ctx.channel().attr(NettyServer.GATE_ACTOR_KEY).get()
            if (gateActor == null) {
                logger.warn { "no GateActor bound, drop msg msgId=${msg.msgId}" }
                return
            }
            gateActor.tell(GateClientMsg(msg), ActorRef.noSender())
        } else {
            ctx.fireChannelRead(msg)
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is WebSocketServerProtocolHandler.HandshakeComplete) {
            NettyServer.onWebSocketReady(ctx)
        } else if (evt is IdleStateEvent && evt.state() == IdleState.ALL_IDLE) {
            logger.info { "idle timeout, close channel ${ctx.channel()}" }
            ctx.close()
        } else {
            ctx.fireUserEventTriggered(evt)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.warn(cause) { "invalid WebSocket connection, closing ${ctx.channel()}" }
        ctx.close()
    }
}
