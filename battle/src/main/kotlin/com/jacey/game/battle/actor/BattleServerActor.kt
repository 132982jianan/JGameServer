package com.jacey.game.battle.actor

import akka.actor.ActorRef
import akka.actor.Props
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.framework.akka.ClusterService
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc

data class BattleServerActorState(
    val battleIdToActor: MutableMap<String, ActorRef> = HashMap(),
    val playerIdToActor: MutableMap<Int, ActorRef> = HashMap(),
)

/** Battle 节点根 Actor：ActorId 目录归根 Actor，每场战斗状态归对应 BaseBattleActor。 */
class BattleServerActor : BaseMessageActor() {
    private val state = BattleServerActorState()

    init {
        registerHandler(NetMessage::class.java) { msg, sender ->
            val battleActor = state.playerIdToActor[msg.userId]
            if (battleActor == null) {
                sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE), self())
            } else {
                battleActor.tell(msg, sender)
            }
        }
        registerHandler(RemoteMessage::class.java) { msg, sender -> onRemote(msg, sender) }
        registerHandler(LocalMessage::class.java) { msg, sender ->
            if (msg.msgId == InternalMessageId.BATTLE_ENDED) {
                finishBattle(msg.lite as? BattleEnded ?: return@registerHandler, sender)
            }
        }
    }

    private fun onRemote(msg: RemoteMessage, sender: ActorRef?) {
        when (msg.msgId) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeBattleServerCreateNewBattle_VALUE -> {
                val request = msg.getProto<RemoteServer.NoticeBattleServerCreateNewBattleRequest>()
                    ?: return replyCreateError(msg.msgId, sender)
                createBattle(request.battleRoomInfo, msg.msgId, sender)
            }
            RemoteServer.RemoteRpcNameEnum.RemoteRpcGateNoticeClientOfflinePush_VALUE -> {
                val offline = msg.getProto<RemoteServer.GateNoticeClientOfflinePush>() ?: return
                state.playerIdToActor[offline.userId]?.tell(
                    LocalMessage(InternalMessageId.BATTLE_PLAYER_OFFLINE, offline.userId),
                    self(),
                )
            }
        }
    }

    private fun createBattle(info: RemoteServer.BattleRoomInfo, msgId: Int, sender: ActorRef?) {
        if (info.battleType != CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer ||
            info.userIdsCount != 2 || state.battleIdToActor.containsKey(info.battleId)
        ) {
            replyCreateError(msgId, sender)
            return
        }
        val actor = context().actorOf(
            Props.create(BaseBattleActor::class.java) { BaseBattleActor(info) },
            "battle-${safeName(info.battleId)}",
        )
        state.battleIdToActor[info.battleId] = actor
        info.userIdsList.forEach { state.playerIdToActor[it] = actor }
        sender?.tell(
            RemoteMessage(
                msgId,
                RemoteServer.NoticeBattleServerCreateNewBattleResponse.newBuilder().setBattleRoomInfo(info),
            ),
            self(),
        )
    }

    private suspend fun finishBattle(ended: BattleEnded, actor: ActorRef?) {
        state.battleIdToActor.remove(ended.battleId)
        ended.playerIds.forEach { playerId ->
            if (state.playerIdToActor[playerId] == actor) state.playerIdToActor.remove(playerId)
        }
        actor?.let(context()::stop)

        val globalId = NacosService.getNodeInfoListByNodeKind(NodeKind.global).minOfOrNull { it.nodeId }
            ?: return
        val notice = RemoteServer.GlobalBattleEndedRequest.newBuilder()
            .setBattleId(ended.battleId)
            .addAllPlayerIds(ended.playerIds)
        ClusterService.tell(
            NodeKind.global,
            globalId,
            RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalBattleEnded_VALUE, notice),
        )
    }

    private fun replyCreateError(msgId: Int, sender: ActorRef?) {
        sender?.tell(
            RemoteMessage(msgId, RemoteServer.RemoteRpcErrorCodeEnum.RemoteRpcServerError_VALUE),
            self(),
        )
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]"), "-").take(80)
}
