package com.jacey.game.chat.actor

import akka.actor.ActorRef
import com.jacey.game.chat.service.BattleChatRoomManagerService
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.BattleInfoService

/**
 * 聊天服主 Actor（原 ChatServerActor）
 */
class ChatServerActor : BaseMessageActor() {

    init {
        registerHandler(RemoteMessage::class.java) { msg, sender -> onRemote(msg, sender) }
        registerHandler(NetMessage::class.java) { msg, sender -> onDispatchNetMessage(msg, sender) }
    }

    private suspend fun onRemote(msg: RemoteMessage, sender: ActorRef?) {
        when (msg.msgId) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcGatewayNoticeClientOfflinePush_VALUE -> {
                val push = msg.getProto<RemoteServer.GatewayNoticeClientOfflinePush>()
                BattleChatRoomManagerService.removeGatewayResponseActor(push?.sessionId ?: 0)
            }

            // battle 通知创建对战聊天室（原 ChatRoomManagerProxy 的远程入口）
            RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeChatServerCreateNewBattleChatRoom_VALUE -> {
                val request = msg.getProto<RemoteServer.NoticeChatServerCreateNewBattleChatRoomRequest>() ?: return
                val chatRoomInfo = request.chatRoomInfo
                val battleId = chatRoomInfo.battleId
                when (chatRoomInfo.chatRoomType.number) {
                    CommonEnum.ChatRoomTypeEnum.TwoPlayerBattleChatRoomType_VALUE -> {
                        val actor = AkkaService.create<BattleChatRoomActor>(
                            "chatRoom-$battleId"
                        )
                        BattleChatRoomManagerService.addChatRoomActor(battleId, actor)
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

                    else -> {
                        logger.error { "not support chatRoomType=${chatRoomInfo.chatRoomType}" }
                        sender?.tell(
                            RemoteMessage(
                                RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeChatServerCreateNewBattleChatRoom_VALUE,
                                RemoteServer.RemoteRpcErrorCodeEnum.RemoteRpcServerError_VALUE
                            ),
                            ActorRef.noSender()
                        )
                    }
                }
            }
        }
    }

    /** 客户端聊天请求分发（原 ChatRoomManagerProxy.onNet / dispatchNetMessage） */
    private suspend fun onDispatchNetMessage(msg: NetMessage, sender: ActorRef?) {
        when (msg.msgId) {
            Rpc.RpcNameEnum.JoinChatRoom_VALUE,
            Rpc.RpcNameEnum.BattleChatText_VALUE -> {
                val userId = msg.userId
                val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
                val chatRoom = battleId?.let { BattleChatRoomManagerService.getChatRoomActor(it) }
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
                BattleChatRoomManagerService.addGatewayResponseActor(msg.sessionId, sender)
                chatRoom.tell(msg, sender)
            }
        }
    }


}