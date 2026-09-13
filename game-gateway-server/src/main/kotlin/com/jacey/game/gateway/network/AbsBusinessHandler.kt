package com.jacey.game.gateway.network

import akka.actor.ActorRef
import akka.actor.Props
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.process.Dispatcher
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.gateway.actor.ClientSessionActor
import com.jacey.game.gateway.session.ClientSession
import com.jacey.game.gateway.session.SessionManagerService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 公共业务入站处理：NetMessage → ClientSessionActor */
abstract class AbsBusinessHandler : ChannelInboundHandlerAdapter() {
    private val logger = KotlinLogging.logger {}

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val channel = ctx.channel()
        val session = SessionManagerService.remove(channel)
        val sessionId = SessionManagerService.sessionIdOf(channel)
        if (session != null && sessionId != null) {
            // 断线处理含挂起 Redis/远端通知，异步调度，不阻塞 netty event loop
            CoroutineScope(Dispatcher.Actor).launch {
                SessionManagerService.removeSession(sessionId)
            }
        }
        ctx.fireChannelInactive()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is NetMessage -> {
                val session = SessionManagerService.sessionOf(ctx.channel())
                if (session == null) {
                    logger.warn { "no session bound, drop msg rpcNum=${msg.msgId}" }
                    return
                }

                // 每个连接只创建一次 session actor（channel attribute 缓存）
                var clientSessionActorRef = ctx.channel().attr(NettyServer.SESSION_ACTOR_KEY).get()
                if (clientSessionActorRef == null) {
                    clientSessionActorRef = newClientSessionActor(session)
                    ctx.channel().attr(NettyServer.SESSION_ACTOR_KEY).set(clientSessionActorRef)
                }

                // 发给客户端Actor
                clientSessionActorRef.tell(msg, null)
            }

            else -> ctx.fireChannelRead(msg)
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




    private fun newClientSessionActor(session: ClientSession): ActorRef =
        AkkaService.system.actorOf(
            Props.create(ClientSessionActor::class.java) { ClientSessionActor(session) },
            getClientSessionActorPrefix() + session.channel.id().asShortText()
        )

    abstract fun getClientSessionActorPrefix(): String
}