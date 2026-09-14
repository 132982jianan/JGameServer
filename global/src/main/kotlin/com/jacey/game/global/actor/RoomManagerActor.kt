package com.jacey.game.global.actor

import akka.actor.ActorRef
import akka.actor.Props
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage

/** 管理 Global 上的全部聊天房间；每个房间对应一个 RoomActor。 */
data class RoomManagerActorState(
    val battleIdToRoom: MutableMap<String, ActorRef> = HashMap(),
    val playerIdToRoom: MutableMap<Int, ActorRef> = HashMap(),
)

class RoomManagerActor : BaseMessageActor() {
    private val state = RoomManagerActorState()

    init {
        registerHandler(LocalMessage::class.java) { msg, sender -> onLocal(msg, sender) }
        registerHandler(NetMessage::class.java) { msg, sender ->
            val room = state.playerIdToRoom[msg.userId]
            if (room == null) {
                sender?.tell(
                    NetMessage(msg.msgId, com.jacey.game.common.proto3.Rpc.RpcErrorCodeEnum.BattleChatTextErrorNotJoinBattle_VALUE),
                    self(),
                )
            } else {
                room.tell(msg, sender)
            }
        }
    }

    private fun onLocal(msg: LocalMessage, sender: ActorRef?) {
        when (msg.msgId) {
            InternalMessageId.GLOBAL_BATTLE_CREATED -> {
                createRoom(msg.lite as? GlobalBattleCreated ?: return)
                sender?.tell(LocalMessage(InternalMessageId.GLOBAL_BATTLE_CREATED, true), self())
            }
            InternalMessageId.GLOBAL_BATTLE_ENDED -> removeRoom(msg.lite as? GlobalBattleEnded ?: return)
            InternalMessageId.GLOBAL_CHAT_PUSH -> context().parent().tell(msg, self())
        }
    }

    private fun createRoom(created: GlobalBattleCreated) {
        val room = state.battleIdToRoom[created.battleId] ?: context().actorOf(
            Props.create(RoomActor::class.java) { RoomActor(created.battleId, created.playerIds) },
            "room-${safeName(created.battleId)}",
        ).also { state.battleIdToRoom[created.battleId] = it }
        created.playerIds.forEach { state.playerIdToRoom[it] = room }
    }

    private fun removeRoom(ended: GlobalBattleEnded) {
        ended.playerIds.forEach { state.playerIdToRoom.remove(it) }
        state.battleIdToRoom.remove(ended.battleId)?.let(context()::stop)
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]"), "-").take(80)
}
