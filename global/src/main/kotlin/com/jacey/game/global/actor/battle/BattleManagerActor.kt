package com.jacey.game.global.actor.battle

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.framework.akka.askAwait
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.global.actor.global.msg.GlobalBattleCreated
import com.jacey.game.global.actor.global.msg.GlobalBattleEnded

data class PlayerBattleLocation(
    val battleId: String,
    val battleServerId: Int,
)

data class BattleManagerActorState(
    val playerLocation: MutableMap<Int, PlayerBattleLocation> = HashMap(),
    val battlePlayers: MutableMap<String, List<Int>> = HashMap(),
)

/** Global 唯一的在线战局目录；所有易失路由均只存在 ActorState。 */
class BattleManagerActor(private val roomManagerActor: ActorRef) : BaseMessageActor() {
    private val state = BattleManagerActorState()

    init {
        registerHandler(LocalMessage::class.java) { msg, sender -> onLocal(msg, sender) }
        registerHandler(NetMessage::class.java) { msg, sender -> routeToBattle(msg, sender) }
        registerHandler(RemoteMessage::class.java) { msg, sender -> onRemote(msg, sender) }
    }

    private suspend fun onLocal(msg: LocalMessage, sender: ActorRef?) {
        when (msg.msgId) {
            InternalMessageId.GLOBAL_BATTLE_CREATED -> {
                val created = msg.lite as? GlobalBattleCreated ?: return
                state.battlePlayers[created.battleId] = created.playerIds
                created.playerIds.forEach {
                    state.playerLocation[it] = PlayerBattleLocation(created.battleId, created.battleServerId)
                }
                val roomReady = roomManagerActor.askAwait(msg) as? LocalMessage
                sender?.tell(
                    LocalMessage(
                        InternalMessageId.GLOBAL_BATTLE_CREATED,
                        roomReady?.lite == true,
                    ),
                    self(),
                )
            }
            InternalMessageId.GLOBAL_BATTLE_ENDED -> removeBattle(msg.lite as? GlobalBattleEnded ?: return)
            InternalMessageId.GLOBAL_PLAYER_BATTLE_QUERY -> {
                val playerId = msg.lite as? Int ?: return
                sender?.tell(
                    LocalMessage(InternalMessageId.GLOBAL_PLAYER_BATTLE_QUERY, state.playerLocation[playerId]),
                    self(),
                )
            }
        }
    }

    private suspend fun routeToBattle(msg: NetMessage, sender: ActorRef?) {
        val location = state.playerLocation[msg.userId]
        val battleActor = location?.let {
            NacosService.getActorRefByNodeKindAndNodeId(NodeKind.battle, it.battleServerId)
        }
        if (battleActor == null) {
            sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE), self())
            return
        }
        battleActor.tell(msg, sender)
    }

    private suspend fun onRemote(msg: RemoteMessage, sender: ActorRef?) {
        when (msg.msgId) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalPlayerOffline_VALUE -> {
                val offline = msg.getProto<RemoteServer.GlobalPlayerOfflineRequest>() ?: return
                val location = state.playerLocation[offline.playerId] ?: return
                val battleActor = NacosService.getActorRefByNodeKindAndNodeId(
                    NodeKind.battle,
                    location.battleServerId,
                ) ?: return
                val push = RemoteServer.GateNoticeClientOfflinePush.newBuilder()
                    .setUserId(offline.playerId)
                    .setIsUserOffline(true)
                battleActor.tell(
                    RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcGateNoticeClientOfflinePush_VALUE, push),
                    self(),
                )
            }
            RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalBattleEnded_VALUE -> {
                val ended = msg.getProto<RemoteServer.GlobalBattleEndedRequest>() ?: return
                removeBattle(GlobalBattleEnded(ended.battleId, ended.playerIdsList))
            }
            RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalPlayerStateQuery_VALUE -> {
                val query = msg.getProto<RemoteServer.GlobalPlayerStateQueryRequest>() ?: return
                val location = state.playerLocation[query.playerId]
                val userState = CommonMsg.UserState.newBuilder()
                    .setOnlineState(CommonEnum.UserOnlineStateEnum.Online)
                if (location == null) {
                    userState.actionState = CommonEnum.UserActionStateEnum.ActionNone
                } else {
                    userState.actionState = CommonEnum.UserActionStateEnum.Playing
                    userState.battleType = CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer
                    userState.battleId = location.battleId
                }
                val response = RemoteServer.GlobalPlayerStateQueryResponse.newBuilder()
                    .setUserState(userState)
                sender?.tell(RemoteMessage(msg.msgId, response), self())
            }
        }
    }

    private fun removeBattle(ended: GlobalBattleEnded) {
        state.battlePlayers.remove(ended.battleId)
        ended.playerIds.forEach { playerId ->
            if (state.playerLocation[playerId]?.battleId == ended.battleId) {
                state.playerLocation.remove(playerId)
            }
        }
        roomManagerActor.tell(
            LocalMessage(InternalMessageId.GLOBAL_BATTLE_ENDED, ended),
            self(),
        )
    }
}
