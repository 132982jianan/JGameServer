package com.jacey.game.gate.netty

import akka.actor.ActorRef
import akka.actor.Props
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.gate.actor.GateActor
import com.jacey.game.gate.actor.GateActorState
import com.jacey.game.gate.actor.GateClientMsg
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent

/** 公共业务入站处理：NetMessage → GateActor */
abstract class AbsBusinessHandler : ChannelInboundHandlerAdapter() {
    private val logger = KotlinLogging.logger {}

    override fun channelInactive(ctx: ChannelHandlerContext) {
        ctx.channel().attr(NettyServer.GATE_ACTOR_KEY).getAndSet(null)
            ?.tell(LocalMessage(InternalMessageId.GATE_DISCONNECTED), ActorRef.noSender())
        ctx.fireChannelInactive()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is NetMessage -> {
                val gateActor = ctx.channel().attr(NettyServer.GATE_ACTOR_KEY).get()
                if (gateActor == null) {
                    logger.warn { "no GateActor bound, drop msg msgId=${msg.msgId}" }
                    return
                }
                gateActor.tell(GateClientMsg(msg), ActorRef.noSender())
            }

            else -> {
                ctx.fireChannelRead(msg)
            }
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent && evt.state() == IdleState.ALL_IDLE) {
            logger.info { "idle timeout, close channel ${ctx.channel()}" }
            ctx.close()
        } else {
            super.userEventTriggered(ctx, evt)
        }
    }


    fun newGateActor(state: GateActorState): ActorRef =
        AkkaService.system.actorOf(
            Props.create(GateActor::class.java) { GateActor(state) },
            getGateActorPrefix() + state.channel.id().asShortText()
        )

    abstract fun getGateActorPrefix(): String
}
