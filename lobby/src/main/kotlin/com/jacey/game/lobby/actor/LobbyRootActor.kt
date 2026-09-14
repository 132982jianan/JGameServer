package com.jacey.game.lobby.actor

import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Terminated
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.framework.akka.askAwait
import com.jacey.game.common.framework.process.Exit
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.AccountId
import com.jacey.game.db.LoginType
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

data class LobbyPlayerActorKey(val playerId: Int, val accountId: String)

data class LobbyRootActorState(
    val accountIdToActor: MutableMap<String, ActorRef> = HashMap(),
    val playerIdToActor: MutableMap<Int, ActorRef> = HashMap(),
    val playerIdToAccountId: MutableMap<Int, String> = HashMap(),
)

/** Nacos 暴露的 Lobby 根 Actor；独占本 Lobby 的 ActorId -> ActorRef 目录。 */
class LobbyRootActor : BaseMessageActor() {
    private val state = LobbyRootActorState()

    init {
        registerHandler(NetMessage::class.java) { msg, sender -> route(msg, sender) }
        registerHandler(LocalMessage::class.java) { msg, sender -> onLocal(msg, sender) }
    }

    override fun preStart() {
        super.preStart()
        val rootActor = self()
        Exit.addExitListener {
            runCatching {
                rootActor.askAwait(LocalMessage(InternalMessageId.LOBBY_FORCE_SAVE_ALL), 60.seconds)
            }
        }
    }

    override suspend fun onTerminated(terminated: Terminated) {
        state.accountIdToActor.entries.removeIf { it.value == terminated.actor() }
        val stoppedPlayerIds = state.playerIdToActor
            .filterValues { it == terminated.actor() }
            .keys
        stoppedPlayerIds.forEach { playerId ->
            state.playerIdToActor.remove(playerId)
            state.playerIdToAccountId.remove(playerId)
        }
    }

    private suspend fun route(msg: NetMessage, sender: ActorRef?) {
        when (msg.msgId) {
            Rpc.RpcNameEnum.Login_VALUE -> {
                val loginName = msg.getProto<CommonMsg.LoginRequest>()?.loginName?.trim().orEmpty()
                if (loginName.isEmpty()) {
                    sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.ClientError_VALUE), self())
                    return
                }
                val accountId = AccountId.createAccountIdByLoginTypeAndLoginName(
                    LoginType.LoginDebug,
                    loginName,
                ).toString()
                accountActor(accountId).tell(msg, sender)
            }

            InternalMessageId.ACCOUNT_LOGOUT -> {
                val byAccountId = msg.data
                    ?.takeIf { it.isNotEmpty() }
                    ?.toString(Charsets.UTF_8)
                    ?.let(state.accountIdToActor::get)
                val target = byAccountId ?: state.playerIdToAccountId[msg.userId]
                    ?.let(state.accountIdToActor::get)
                target?.tell(msg, sender)
            }

            else -> {
                val player = state.playerIdToActor[msg.userId]
                if (player == null) {
                    sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.ClientError_VALUE), self())
                } else {
                    player.tell(msg, sender)
                }
            }
        }
    }

    private suspend fun onLocal(msg: LocalMessage, sender: ActorRef?) {
        when (msg.msgId) {
            InternalMessageId.LOBBY_GET_OR_CREATE_PLAYER -> {
                val key = msg.lite as? LobbyPlayerActorKey ?: return
                val actor = playerActor(key.playerId, key.accountId)
                sender?.tell(LocalMessage(msg.msgId, actor), self())
            }

            InternalMessageId.LOBBY_FORCE_SAVE_ALL -> {
                val success = state.playerIdToActor.values.toList().all { actor ->
                    runCatching {
                        val reply = actor.askAwait(
                            LocalMessage(InternalMessageId.PLAYER_FORCE_SAVE),
                            10.seconds,
                        ) as? LocalMessage
                        reply?.lite == true
                    }.getOrDefault(false)
                }
                sender?.tell(LocalMessage(msg.msgId, success), self())
            }
        }
    }

    private fun accountActor(accountId: String): ActorRef {
        state.accountIdToActor[accountId]?.let { return it }
        val actor = context().actorOf(
            Props.create(AccountActor::class.java) { AccountActor(accountId, self()) },
            "account-${sha256(accountId).take(24)}",
        )
        context().watch(actor)
        state.accountIdToActor[accountId] = actor
        return actor
    }

    private fun playerActor(playerId: Int, accountId: String): ActorRef {
        state.playerIdToActor[playerId]?.let { return it }
        val actor = context().actorOf(
            Props.create(PlayerActor::class.java) { PlayerActor(playerId, accountId) },
            "player-$playerId",
        )
        context().watch(actor)
        state.playerIdToActor[playerId] = actor
        state.playerIdToAccountId[playerId] = accountId
        return actor
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
