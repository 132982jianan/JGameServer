package com.jacey.game.chat

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.BattleInfoService

/**
 * 聊天服状态（原 chat OnlineClientManager）
 * - battleId -> 聊天室 actor
 * - sessionId -> gateway ResponseActor
 */
object ChatRooms {
    private val battleIdToChatRoomActor = java.util.concurrent.ConcurrentHashMap<String, ActorRef>()
    private val sessionIdToGatewayResponseActor = java.util.concurrent.ConcurrentHashMap<Int, ActorRef>()

    val chatRoomCount: Int get() = battleIdToChatRoomActor.size

    fun getChatRoomActor(battleId: String): ActorRef? = battleIdToChatRoomActor[battleId]

    suspend fun addChatRoomActor(battleId: String, actor: ActorRef) {
        battleIdToChatRoomActor[battleId] = actor
        // battleId <-> chatServerId 绑定
        BattleInfoService.setOneBattleIdToChatServerId(battleId, NacosService.selfNodeId)
    }

    suspend fun removeChatRoomActor(battleId: String) {
        battleIdToChatRoomActor.remove(battleId)
        BattleInfoService.setOneBattleIdToChatServerId(battleId, 0)
    }

    fun addGatewayResponseActor(sessionId: Int, actor: ActorRef?) {
        if (actor != null) sessionIdToGatewayResponseActor[sessionId] = actor
    }

    fun getGatewayResponseActor(sessionId: Int): ActorRef? = sessionIdToGatewayResponseActor[sessionId]

    fun removeGatewayResponseActor(sessionId: Int) {
        sessionIdToGatewayResponseActor.remove(sessionId)
    }
}

/**
 * 消息推送（原 chat MessageRouter 推送部分）
 */
object ChatMessageRouter {

    /** 推送消息到 userId 对应客户端 */
    suspend fun sendNetMsgToOneUser(userId: Int, netMsg: NetMessage): Boolean {
        val sessionId = com.jacey.game.db.redis.SessionIdRedis.getOneUserIdToSessionId(userId)
        return if (sessionId != null) {
            val actor = ChatRooms.getGatewayResponseActor(sessionId)
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

/**
 * 聊天服主 Actor（原 ChatServerActor）
 */
class ChatServerActor : BaseMessageActor() {

    init {
        registerHandler(RemoteMessage::class.java) { msg, sender -> onRemote(msg, sender) }
        registerHandler(NetMessage::class.java) { msg, sender -> dispatchNetMessage(msg, sender) }
    }

    private suspend fun onRemote(msg: RemoteMessage, sender: ActorRef?) {
        when (msg.msgId) {

            RemoteServer.RemoteRpcNameEnum.RemoteRpcGatewayNoticeClientOfflinePush_VALUE -> {
                val push = msg.getProto<RemoteServer.GatewayNoticeClientOfflinePush>()
                ChatRooms.removeGatewayResponseActor(push?.sessionId ?: 0)
            }

            // battle 通知创建对战聊天室（原 ChatRoomManagerProxy 的远程入口）
            RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeChatServerCreateNewBattleChatRoom_VALUE -> {
                val request = msg.getProto<RemoteServer.NoticeChatServerCreateNewBattleChatRoomRequest>() ?: return
                val chatRoomInfo = request.chatRoomInfo
                val battleId = chatRoomInfo.battleId
                when (chatRoomInfo.chatRoomType.number) {
                    CommonEnum.ChatRoomTypeEnum.TwoPlayerBattleChatRoomType_VALUE -> {
                        val actor = com.jacey.game.common.framework.akka.AkkaService.create<BaseBattleChatRoomActor>(
                            "chatRoom-$battleId"
                        )
                        ChatRooms.addChatRoomActor(battleId, actor)
                        val response = RemoteServer.NoticeChatServerCreateNewBattleChatRoomResponse.newBuilder()
                        sender?.tell(
                            RemoteMessage(
                                RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeChatServerCreateNewBattleChatRoom_VALUE,
                                response
                            ),
                            ActorRef.noSender()
                        )
                        logger.info { "对战聊天室初始化完成 battleId=$battleId" }
                    }

                    else -> logger.error { "not support chatRoomType=${chatRoomInfo.chatRoomType}" }
                }
            }
        }
    }

    /** 客户端聊天请求分发（原 ChatRoomManagerProxy.onNet / dispatchNetMessage） */
    private suspend fun dispatchNetMessage(msg: NetMessage, sender: ActorRef?) {
        when (msg.msgId) {
            com.jacey.game.common.proto3.Rpc.RpcNameEnum.JoinChatRoom_VALUE,
            com.jacey.game.common.proto3.Rpc.RpcNameEnum.BattleChatText_VALUE -> {
                val userId = msg.userId
                val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
                val chatRoom = battleId?.let { ChatRooms.getChatRoomActor(it) }
                if (chatRoom == null) {
                    sender?.tell(
                        NetMessage(
                            msg.msgId,
                            Rpc.RpcErrorCodeEnum.ServerError_VALUE
                        ),
                        null
                    )
                    return
                }
                ChatRooms.addGatewayResponseActor(msg.sessionId, sender)
                chatRoom.tell(msg, sender)
            }
        }
    }


}
