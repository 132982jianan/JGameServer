package com.jacey.game.global.actor

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc

/** 一个聊天房间；当前首个房间类型是战斗聊天室。 */
class RoomActor(
    private val battleId: String,
    private val allowedPlayerIds: List<Int>,
) : BaseMessageActor() {
    private val members = mutableSetOf<Int>()

    init {
        registerHandler(NetMessage::class.java) { msg, sender -> onNetMessage(msg, sender) }
    }

    private suspend fun onNetMessage(msg: NetMessage, sender: ActorRef?) {
        when (msg.msgId) {
            Rpc.RpcNameEnum.JoinChatRoom_VALUE -> {
                if (msg.userId in allowedPlayerIds) {
                    members.add(msg.userId)
                    sender?.tell(NetMessage(msg.msgId, CommonMsg.JoinChatRoomResponse.newBuilder()), self())
                } else {
                    sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.BattleChatTextErrorNotJoinBattle_VALUE), self())
                }
            }

            Rpc.RpcNameEnum.BattleChatText_VALUE -> {
                val request = msg.getProto<CommonMsg.BattleChatTextSendRequest>()
                if (request == null || msg.userId !in members) {
                    sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.BattleChatTextErrorNotJoinBattle_VALUE), self())
                    return
                }
                val push = CommonMsg.BattleChatTextPush.newBuilder()
                    .setSenderUserId(msg.userId)
                    .setBattleChatTextScope(request.battleChatTextScope)
                    .setText(request.text)
                    .setSendTimestamp(request.sendTimestamp)
                val netPush = NetMessage(Rpc.RpcNameEnum.RpcBattleChatTextPush_VALUE, push)
                allowedPlayerIds.filter { it != msg.userId }
                    .filter(members::contains)
                    .forEach {
                        context().parent().tell(
                            LocalMessage(InternalMessageId.GLOBAL_CHAT_PUSH, GlobalChatPush(it, netPush)),
                            self(),
                        )
                    }
                sender?.tell(NetMessage(msg.msgId, CommonMsg.BattleChatTextSendResponse.newBuilder()), self())
            }
        }
    }

}
