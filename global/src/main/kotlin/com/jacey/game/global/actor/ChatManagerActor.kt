package com.jacey.game.global.actor

import akka.actor.ActorRef
import akka.actor.Props
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.RemoteServer

/** 在线玩家聊天 Actor 管理器；每个在线玩家对应一个 ChatActor。 */
data class ChatManagerActorState(
    val playerIdToActor: MutableMap<Int, ActorRef> = HashMap(),
    val playerIdToName: MutableMap<Int, String> = HashMap(),
)

class ChatManagerActor(private val roomManagerActor: ActorRef) : BaseMessageActor() {
    private val state = ChatManagerActorState()

    init {
        registerHandler(NetMessage::class.java) { msg, sender ->
            chatActor(msg.userId).tell(msg, sender)
        }
        registerHandler(LocalMessage::class.java) { msg, sender ->
            when (msg.msgId) {
                InternalMessageId.GLOBAL_CHAT_PUSH -> {
                    val push = msg.lite as? GlobalChatPush ?: return@registerHandler
                    state.playerIdToActor[push.playerId]?.tell(push.message, sender)
                }
                InternalMessageId.GLOBAL_PLAYER_NAME_QUERY -> {
                    val playerId = msg.lite as? Int ?: return@registerHandler
                    sender?.tell(
                        LocalMessage(InternalMessageId.GLOBAL_PLAYER_NAME_QUERY, state.playerIdToName[playerId]),
                        self(),
                    )
                }
            }
        }
        registerHandler(RemoteMessage::class.java) { msg, _ ->
            when (msg.msgId) {
                RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalPlayerOnline_VALUE -> {
                    val request = msg.getProto<RemoteServer.GlobalPlayerOnlineRequest>() ?: return@registerHandler
                    state.playerIdToName[request.playerId] = request.playerName
                    chatActor(request.playerId)
                }
                RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalPlayerOffline_VALUE -> {
                    val request = msg.getProto<RemoteServer.GlobalPlayerOfflineRequest>() ?: return@registerHandler
                    state.playerIdToName.remove(request.playerId)
                    state.playerIdToActor.remove(request.playerId)?.let(context()::stop)
                }
            }
        }
    }

    private fun chatActor(playerId: Int): ActorRef {
        state.playerIdToActor[playerId]?.let { return it }
        val actor = context().actorOf(
            Props.create(ChatActor::class.java) { ChatActor(playerId, roomManagerActor) },
            "chat-$playerId",
        )
        state.playerIdToActor[playerId] = actor
        return actor
    }
}
