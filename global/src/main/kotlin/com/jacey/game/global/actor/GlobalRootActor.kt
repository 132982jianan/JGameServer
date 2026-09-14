package com.jacey.game.global.actor

import akka.actor.ActorRef
import akka.actor.Props
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.global.match.MatchActor

/** Global 单实例对外根 Actor，内部拆分 MatchActor、ChatManagerActor 与 RoomManagerActor。 */
class GlobalRootActor : BaseMessageActor() {
    private lateinit var matchActor: ActorRef
    private lateinit var chatManagerActor: ActorRef
    private lateinit var roomManagerActor: ActorRef
    private lateinit var battleManagerActor: ActorRef

    init {
        registerHandler(LocalMessage::class.java) { msg, sender ->
            if (msg.msgId == InternalMessageId.GLOBAL_CHAT_PUSH) chatManagerActor.tell(msg, sender)
        }
        registerHandler(NetMessage::class.java) { msg, sender ->
            when (msg.msgId) {
                Rpc.RpcNameEnum.Match_VALUE,
                Rpc.RpcNameEnum.CancelMatch_VALUE -> matchActor.tell(msg, sender)
                in 6000..6999 -> battleManagerActor.tell(msg, sender)
                else -> chatManagerActor.tell(msg, sender)
            }
        }
        registerHandler(RemoteMessage::class.java) { msg, sender ->
            when (msg.msgId) {
                RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalPlayerOffline_VALUE -> {
                    chatManagerActor.tell(msg, sender)
                    battleManagerActor.tell(msg, sender)
                    matchActor.tell(msg, sender)
                }
                RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalBattleEnded_VALUE ->
                    battleManagerActor.tell(msg, sender)
                RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalPlayerStateQuery_VALUE ->
                    battleManagerActor.tell(msg, sender)
                else -> chatManagerActor.tell(msg, sender)
            }
        }
    }

    override fun preStart() {
        super.preStart()
        roomManagerActor = context().actorOf(Props.create(RoomManagerActor::class.java), "roomManagerActor")
        chatManagerActor = context().actorOf(
            Props.create(ChatManagerActor::class.java) { ChatManagerActor(roomManagerActor) },
            "chatManagerActor",
        )
        battleManagerActor = context().actorOf(
            Props.create(BattleManagerActor::class.java) { BattleManagerActor(roomManagerActor) },
            "battleManagerActor",
        )
        matchActor = context().actorOf(
            Props.create(MatchActor::class.java) { MatchActor(battleManagerActor, chatManagerActor) },
            "matchActor",
        )
    }
}
