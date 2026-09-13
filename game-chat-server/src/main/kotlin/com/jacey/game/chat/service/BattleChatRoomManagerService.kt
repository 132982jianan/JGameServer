package com.jacey.game.chat.service

import akka.actor.ActorRef
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.db.service.BattleInfoService
import java.util.concurrent.ConcurrentHashMap

/**
 * 聊天服状态（原 chat OnlineClientManager）
 * - battleId -> 聊天室 actor
 * - sessionId -> gateway ResponseActor
 */
object BattleChatRoomManagerService {
    private val battleIdToChatRoomActor = ConcurrentHashMap<String, ActorRef>()
    private val sessionIdToGatewayResponseActor = ConcurrentHashMap<Int, ActorRef>()

    fun getChatRoomActor(battleId: String): ActorRef? {
        return battleIdToChatRoomActor[battleId]
    }

    suspend fun addChatRoomActor(battleId: String, actor: ActorRef) {
        battleIdToChatRoomActor[battleId] = actor
        // battleId <-> chatServerId 绑定
        BattleInfoService.setOneBattleIdToChatServerId(battleId, NacosService.selfNodeId)
    }

    fun addGatewayResponseActor(sessionId: Int, actor: ActorRef?) {
        if (actor != null) {
            sessionIdToGatewayResponseActor[sessionId] = actor
        }
    }

    fun getGatewayResponseActor(sessionId: Int): ActorRef? {
        return sessionIdToGatewayResponseActor[sessionId]
    }

    fun removeGatewayResponseActor(sessionId: Int) {
        sessionIdToGatewayResponseActor.remove(sessionId)
    }
}