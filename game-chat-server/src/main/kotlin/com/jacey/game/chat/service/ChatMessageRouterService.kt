package com.jacey.game.chat.service

import akka.actor.ActorRef
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.db.redis.SessionIdRedis

/**
 * 消息推送（原 chat MessageRouter 推送部分）
 */
object ChatMessageRouterService {

    /** 推送消息到 userId 对应客户端 */
    suspend fun sendNetMsgToOneUser(userId: Int, netMsg: NetMessage): Boolean {
        val sessionId = SessionIdRedis.getOneUserIdToSessionId(userId)
        return if (sessionId != null) {
            val actor = BattleChatRoomManagerService.getGatewayResponseActor(sessionId)
            if (actor != null) {
                actor.tell(netMsg, ActorRef.noSender())
                true
            } else {
                false
            }
        } else {
            false
        }
    }
}