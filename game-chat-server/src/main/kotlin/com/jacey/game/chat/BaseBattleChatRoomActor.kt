package com.jacey.game.chat

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.BattleInfoService

/**
 * 对战聊天室 Actor（原 BaseBattleChatRoomActor）：一场对战的聊天处理
 */
class BaseBattleChatRoomActor : BaseMessageActor() {

    init {
        registerHandler(NetMessage::class.java) { msg, sender -> onNetMessage(msg, sender) }
    }

    private suspend fun onNetMessage(msg: NetMessage, sender: ActorRef?) {
        when (msg.rpcNum) {
            Rpc.RpcNameEnum.JoinChatRoom_VALUE -> {
                val response = CommonMsg.JoinChatRoomResponse.newBuilder()
                sender?.tell(NetMessage(Rpc.RpcNameEnum.JoinChatRoom_VALUE, response), null)
            }
            Rpc.RpcNameEnum.BattleChatText_VALUE -> {
                val request = msg.getProto<CommonMsg.BattleChatTextSendRequest>()
                if (request == null) {
                    sender?.tell(NetMessage(msg.rpcNum, Rpc.RpcErrorCodeEnum.ServerError_VALUE), null)
                    return
                }
                val userId = msg.userId
                val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
                if (battleId == null) {
                    sender?.tell(
                        NetMessage(Rpc.RpcNameEnum.BattleChatText_VALUE, Rpc.RpcErrorCodeEnum.BattleChatTextErrorNotJoinBattle_VALUE),
                        null
                    )
                    return
                }
                when (request.chatRoomType.number) {
                    CommonEnum.ChatRoomTypeEnum.TwoPlayerBattleChatRoomType_VALUE -> {
                        val opponentUserIds = BattleInfoService.getOneUserAllOpponentUserIds(battleId, userId)
                        val pushBuilder = CommonMsg.BattleChatTextPush.newBuilder()
                            .setSenderUserId(userId)
                            .setBattleChatTextScope(request.battleChatTextScope)
                            .setText(request.text)
                            .setSendTimestamp(request.sendTimestamp)
                        val netMsg = NetMessage(23001, pushBuilder) // RpcBattleChatTextPush
                        for (opponentId in opponentUserIds) {
                            ChatMessageRouter.sendNetMsgToOneUser(opponentId, netMsg)
                        }
                    }
                    else -> logger.error { "not support ChatRoomType=${request.chatRoomType}" }
                }
                val response = CommonMsg.BattleChatTextSendResponse.newBuilder()
                sender?.tell(NetMessage(Rpc.RpcNameEnum.BattleChatText_VALUE, response), null)
            }
        }
    }
}
