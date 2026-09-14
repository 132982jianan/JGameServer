package com.jacey.game.global.actor.chat

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.Rpc

/** 单个在线玩家的聊天上下文；保存 GateActor 引用，不保存 DbPlayer。 */
class ChatActor(
    private val playerId: Int,
    private val roomManagerActor: ActorRef,
) : BaseMessageActor() {
    private var gateActor: ActorRef? = null

    init {
        registerHandler(NetMessage::class.java) { msg, sender ->
            if (msg.msgId == Rpc.RpcNameEnum.RpcBattleChatTextPush_VALUE) {
                gateActor?.tell(msg, self())
                return@registerHandler
            }

            if (sender != null) gateActor = sender
            roomManagerActor.tell(msg, sender)
        }
    }
}
