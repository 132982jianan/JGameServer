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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** WebSocket 会话生命周期与业务入站处理；帧编解码由 WebSocketNetMessageCodec 负责。 */
class WebSocketBusinessHandler : ChannelInboundHandlerAdapter() {
    private val logger = KotlinLogging.logger {}
    private var gateActorRef: ActorRef? = null
    private var sessionSequence: Int? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        val channel = ctx.channel()

        /*
         * 每个 Gate 节点占用一段连续的 sessionId：
         * sessionId = Gate 节点 ID * 1_000_000 + 节点内连接序号。
         * 例如 Gate#3 的第 25 个连接，其 sessionId 为 3_000_025。
         * 节点内序号会循环使用，但分配时会跳过仍在线连接占用的序号。
         */
        val sessionId = allocateSessionId()

        val state = GateActorState(channel, sessionId)
        try {
            gateActorRef = AkkaService.system.actorOf(
                Props.create(GateActor::class.java) {
                    GateActor(state)
                },
                "ws-${channel.id().asShortText()}",
            )
        } catch (error: Exception) {
            releaseSessionSequence()
            throw error
        }
        logger.info { "GateActor created: sessionId=$sessionId ip=${state.userIp}" }
        ctx.fireChannelActive()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        gateActorRef?.tell(LocalMessage(InternalMessageId.GATE_DISCONNECTED), ActorRef.noSender())
        gateActorRef = null
        releaseSessionSequence()
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
            if (!MessageRouterService.isHaveOneLobbyForClient()) {
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

    private fun allocateSessionId(): Int {
        val gateNodeId = NacosService.selfNodeId
        require(gateNodeId in 1..MAX_GATE_NODE_ID) {
            "Gate nodeId must be in 1..$MAX_GATE_NODE_ID to generate an Int sessionId: $gateNodeId"
        }

        repeat(SESSIONS_PER_GATE) {
            val candidate = connectionSequence.updateAndGet { current ->
                if (current >= SESSIONS_PER_GATE) 1 else current + 1
            }
            if (activeSessionSequences.add(candidate)) {
                sessionSequence = candidate
                return Math.addExact(Math.multiplyExact(gateNodeId, SESSIONS_PER_GATE), candidate)
            }
        }
        throw IllegalStateException("Gate#$gateNodeId has exhausted all $SESSIONS_PER_GATE session IDs")
    }

    private fun releaseSessionSequence() {
        sessionSequence?.let(activeSessionSequences::remove)
        sessionSequence = null
    }

    companion object {
        private const val SESSIONS_PER_GATE = 1_000_000
        private const val MAX_GATE_NODE_ID = (Int.MAX_VALUE - SESSIONS_PER_GATE) / SESSIONS_PER_GATE
        private val connectionSequence = AtomicInteger()
        private val activeSessionSequences = ConcurrentHashMap.newKeySet<Int>()
    }
}
