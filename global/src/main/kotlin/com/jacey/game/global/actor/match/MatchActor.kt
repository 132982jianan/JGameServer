package com.jacey.game.global.actor.match

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.framework.akka.ClusterService
import com.jacey.game.common.framework.akka.askAwait
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.global.actor.global.msg.GlobalBattleCreated
import com.jacey.game.global.actor.battle.PlayerBattleLocation
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class MatchPlayer(val playerId: Int, val gateActor: ActorRef)

data class MatchActorState(
    val twoPlayerQueue: ArrayDeque<MatchPlayer> = ArrayDeque(),
    val matchingPlayerIds: MutableSet<Int> = HashSet(),
)

/** Global 单实例匹配 Actor；匹配池是 ActorState，不写 Redis/Mongo。 */
class MatchActor(
    private val battleManagerActor: ActorRef,
    private val chatManagerActor: ActorRef,
) : BaseMessageActor() {
    private val log = KotlinLogging.logger {}
    private val state = MatchActorState()

    init {
        registerHandler(NetMessage::class.java) { msg, sender -> onNetMessage(msg, sender) }
        registerHandler(RemoteMessage::class.java) { msg, _ ->
            if (msg.msgId == RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalPlayerOffline_VALUE) {
                val offline = msg.getProto<RemoteServer.GlobalPlayerOfflineRequest>() ?: return@registerHandler
                removeFromQueue(offline.playerId)
            }
        }
    }

    override fun preStart() {
        super.preStart()
        timer(Duration.ZERO, ::matchTick)
    }

    /** 本轮处理完成后再计时，避免远程调用缓慢时积压匹配 tick。 */
    private suspend fun matchTick() {
        try {
            doMatch()
        } finally {
            timer(1.seconds, ::matchTick)
        }
    }

    private suspend fun onNetMessage(msg: NetMessage, gateActor: ActorRef?) {
        when (msg.msgId) {
            Rpc.RpcNameEnum.Match_VALUE -> addMatch(msg, gateActor)
            Rpc.RpcNameEnum.CancelMatch_VALUE -> cancelMatch(msg, gateActor)
        }
    }

    private suspend fun addMatch(msg: NetMessage, gateActor: ActorRef?) {
        val request = msg.getProto<CommonMsg.MatchRequest>()
        val errorCode = when {
            gateActor == null || request == null -> Rpc.RpcErrorCodeEnum.ServerError_VALUE
            request.battleType != CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer -> Rpc.RpcErrorCodeEnum.ServerError_VALUE
            msg.userId in state.matchingPlayerIds -> Rpc.RpcErrorCodeEnum.MatchErrorMatching_VALUE
            playerBattleLocation(msg.userId) != null -> Rpc.RpcErrorCodeEnum.MatchErrorPlaying_VALUE
            else -> {
                state.twoPlayerQueue.addLast(MatchPlayer(msg.userId, gateActor))
                state.matchingPlayerIds.add(msg.userId)
                Rpc.RpcErrorCodeEnum.Ok_VALUE
            }
        }
        gateActor?.tell(NetMessage(msg.msgId, errorCode), self())
    }

    private fun cancelMatch(msg: NetMessage, gateActor: ActorRef?) {
        val removed = removeFromQueue(msg.userId)
        gateActor?.tell(
            NetMessage(
                msg.msgId,
                if (removed) Rpc.RpcErrorCodeEnum.Ok_VALUE
                else Rpc.RpcErrorCodeEnum.CancelMatchErrorNotMatching_VALUE,
            ),
            self(),
        )
    }

    private fun removeFromQueue(playerId: Int): Boolean {
        val removed = state.twoPlayerQueue.removeIf { it.playerId == playerId }
        if (removed) state.matchingPlayerIds.remove(playerId)
        return removed
    }

    private suspend fun doMatch() {
        if (state.twoPlayerQueue.size < 2) return
        val players = listOf(
            state.twoPlayerQueue.removeFirst(),
            state.twoPlayerQueue.removeFirst(),
        ).shuffled()
        players.forEach { state.matchingPlayerIds.remove(it.playerId) }
        createBattle(players)
    }

    private suspend fun createBattle(players: List<MatchPlayer>) {
        val battleId = "${CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer_VALUE}_" +
            UUID.randomUUID().toString().replace("-", "")
        val playerIds = players.map(MatchPlayer::playerId)
        val battleServerId = NacosService.getNodeInfoListByNodeKind(NodeKind.battle)
            .randomOrNull()?.nodeId
        val created = if (battleServerId != null) {
            val roomInfo = RemoteServer.BattleRoomInfo.newBuilder()
                .setBattleType(CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer)
                .setBattleId(battleId)
                .addAllUserIds(playerIds)
            val request = RemoteServer.NoticeBattleServerCreateNewBattleRequest.newBuilder()
                .setBattleRoomInfo(roomInfo)
            val reply = ClusterService.askAwait(
                NodeKind.battle,
                battleServerId,
                RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeBattleServerCreateNewBattle_VALUE, request),
            )
            reply?.errorCode == RemoteServer.RemoteRpcErrorCodeEnum.RemoteRpcOk_VALUE
        } else false

        val registered = if (created && battleServerId != null) {
            val reply = battleManagerActor.askAwait(
                LocalMessage(
                    InternalMessageId.GLOBAL_BATTLE_CREATED,
                    GlobalBattleCreated(battleId, battleServerId, playerIds),
                )
            ) as? LocalMessage
            reply?.lite == true
        } else false

        if (!registered) {
            log.error { "create battle failed: battleId=$battleId players=$playerIds" }
            pushMatchResult(players, false, battleId)
            return
        }
        pushMatchResult(players, true, battleId)
    }

    private suspend fun pushMatchResult(players: List<MatchPlayer>, success: Boolean, battleId: String) {
        val result = CommonMsg.MatchResultPush.newBuilder()
            .setIsSuccess(success)
            .setBattleType(CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer)
        if (success) result.battleId = battleId
        if (success) {
            for (player in players) {
                val userState = CommonMsg.UserState.newBuilder()
                    .setOnlineState(CommonEnum.UserOnlineStateEnum.Online)
                    .setActionState(CommonEnum.UserActionStateEnum.Playing)
                    .setBattleType(CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer)
                    .setBattleId(battleId)
                result.addUserBriefInfos(
                    CommonMsg.UserBriefInfo.newBuilder()
                        .setUserId(player.playerId)
                        .setNickname(playerName(player.playerId) ?: "Player${player.playerId}")
                        .setUserState(userState)
                )
            }
        }
        val push = NetMessage(Rpc.RpcNameEnum.RpcMatchResultPush_VALUE, result)
        players.forEach { it.gateActor.tell(push, self()) }
    }

    private suspend fun playerName(playerId: Int): String? {
        val reply = chatManagerActor.askAwait(
            LocalMessage(InternalMessageId.GLOBAL_PLAYER_NAME_QUERY, playerId)
        ) as? LocalMessage
        return reply?.lite as? String
    }

    private suspend fun playerBattleLocation(playerId: Int): PlayerBattleLocation? {
        val reply = battleManagerActor.askAwait(
            LocalMessage(InternalMessageId.GLOBAL_PLAYER_BATTLE_QUERY, playerId)
        ) as? LocalMessage
        return reply?.lite as? PlayerBattleLocation
    }
}
