package com.jacey.game.logic.service

import akka.actor.ActorRef
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话索引（object 单例，原 logic OnlineClientManager）
 * sessionId -> gateway ResponseActor（远端响应直接转发给客户端）
 */
object OnlineClientService {
    private val sessionIdToGatewayResponseActor = ConcurrentHashMap<Int, ActorRef>()

    val onlineCount: Int get() = sessionIdToGatewayResponseActor.size

    fun addSessionIdToGatewayResponseActor(sessionId: Int, actor: ActorRef?) {
        if (actor != null) {
            sessionIdToGatewayResponseActor[sessionId] = actor
        }
    }

    fun removeSessionIdToGatewayResponseActor(sessionId: Int) {
        sessionIdToGatewayResponseActor.remove(sessionId)
    }

    fun getGatewayResponseActor(sessionId: Int): ActorRef? = sessionIdToGatewayResponseActor[sessionId]
}