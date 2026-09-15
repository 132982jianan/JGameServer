package com.jacey.game.gate.netty

import akka.actor.ActorRef
import akka.actor.Props
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.gate.actor.GateActor
import com.jacey.game.gate.actor.GateActorState
import com.jacey.game.gate.actor.GateClientMsg
import com.jacey.game.gate.service.MessageRouterService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import java.util.concurrent.atomic.AtomicInteger

/** WebSocket 会话生命周期与业务入站处理；帧编解码由 WebSocketNetMessageCodec 负责。 */
class WebSocketBusinessHandler : ChannelInboundHandlerAdapter() {
    private val logger = KotlinLogging.logger {}
    private var gateActorRef: ActorRef? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        val channel = ctx.channel()

        // TODO
        val sessionId =
            Math.addExact(Math.multiplyExact(NacosService.selfNodeId, 1_000_000), connectionSequence.incrementAndGet())

        val state = GateActorState(channel, sessionId)
        gateActorRef = AkkaService.system.actorOf(
            Props.create(GateActor::class.java) {
                GateActor(state)
            },
            "ws-${channel.id().asShortText()}",
        )
        logger.info { "GateActor created: sessionId=$sessionId ip=${state.userIp}" }
        ctx.fireChannelActive()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        gateActorRef?.tell(LocalMessage(InternalMessageId.GATE_DISCONNECTED), ActorRef.noSender())
        gateActorRef = null
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is NetMessage) {
            if (gateActorRef == null) {
                logger.warn { "no GateActor available, drop msg msgId=${msg.msgId}" }
                return
            }
            gateActorRef?.tell(GateClientMsg(msg), ActorRef.noSender())
        } else {
            ctx.fireChannelRead(msg)
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is WebSocketServerProtocolHandler.HandshakeComplete) {
            if (!MessageRouterService.isAvailableForClient()) {
                val push = CommonMsg.ForceOfflinePush.newBuilder()
                    .setForceOfflineReason(CommonEnum.ForceOfflineReasonEnum.ForceOfflineServerNotAvailable)
                    .build()
                ctx.channel().writeAndFlush(NetMessage(Rpc.RpcNameEnum.RpcForceOfflinePush_VALUE, push))
                    .addListener(ChannelFutureListener.CLOSE)
            }
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

    companion object {
        private val connectionSequence = AtomicInteger()
    }
}
