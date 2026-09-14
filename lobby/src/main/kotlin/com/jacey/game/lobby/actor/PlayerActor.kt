package com.jacey.game.lobby.actor

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.db.Db
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.akka.askAwait
import com.jacey.game.db.table.DbPlayer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.util.Date

data class PlayerLogin(
    val request: NetMessage,
    val loginName: String,
    val gateActor: ActorRef,
    val accountActor: ActorRef,
)

data class PlayerLoginResult(
    val success: Boolean,
    val playerId: Int,
    val playerActor: ActorRef,
)

data class PlayerActorState(
    val dbPlayer: DbPlayer,
    var gateActor: ActorRef,
    var sessionId: Int,
    var nextMinuteTimestamp: Long,
    var lastSuccessfulSaveTimestamp: Long,
)

/** 玩家维度 Actor：独占 DbPlayer 聚合，所有玩家级业务在此串行处理。 */
class PlayerActor(
    private val playerId: Int,
    private val accountId: String,
) : BaseMessageActor() {
    private val log = KotlinLogging.logger {}
    private var state: PlayerActorState? = null

    init {
        registerHandler(LocalMessage::class.java) { msg, sender -> onLocalMessage(msg, sender) }
        registerHandler(NetMessage::class.java) { msg, sender -> onGateMessage(msg, sender) }
    }

    private suspend fun onLocalMessage(msg: LocalMessage, sender: ActorRef?) {
        when (msg.msgId) {
            InternalMessageId.PLAYER_LOGIN -> login(msg.lite as? PlayerLogin ?: return)
            InternalMessageId.PLAYER_LOGOUT -> logout()
            InternalMessageId.PLAYER_FORCE_SAVE -> {
                val success = forceSaveWithRetry()
                sender?.tell(LocalMessage(InternalMessageId.PLAYER_FORCE_SAVE_RESULT, success), self())
            }
        }
    }

    private suspend fun login(command: PlayerLogin) {
        if (state != null) {
            command.accountActor.tell(
                LocalMessage(
                    InternalMessageId.PLAYER_LOGIN_RESULT,
                    PlayerLoginResult(false, playerId, self()),
                ),
                self(),
            )
            return
        }

        val now = System.currentTimeMillis()
        val existing = Db.dbPlayer.findOne(playerId)
        val dbPlayer = existing ?: DbPlayer(
            _id = playerId,
            accountId = accountId,
        )
        val firstLogin = dbPlayer.account.firstLoginTimestamp == null
        if (firstLogin) {
            dbPlayer.account.firstLoginTimestamp = Date(now)
            dbPlayer.basic.name = command.loginName
        }
        dbPlayer.account.lastLoginTimestamp = Date(now)
        dbPlayer.account.lastLoginIp = command.request.userIp.orEmpty()

        state = PlayerActorState(
            dbPlayer = dbPlayer,
            gateActor = command.gateActor,
            sessionId = command.request.sessionId,
            nextMinuteTimestamp = PlayerHeartbeatService.nextMinuteTimestamp(now),
            // 首次登录和老玩家均不立即写库；至少经过五分钟保存冷却。
            lastSuccessfulSaveTimestamp = now,
        )

        notifyGlobalOnline(dbPlayer.basic.name)

        val userState = queryGlobalPlayerState() ?: CommonMsg.UserState.newBuilder()
            .setOnlineState(CommonEnum.UserOnlineStateEnum.Online)
            .setActionState(CommonEnum.UserActionStateEnum.ActionNone)
            .build()
        val userInfo = CommonMsg.UserInfo.newBuilder()
            .setUserId(playerId)
            .setUsername(command.loginName)
            .setNickname(dbPlayer.basic.name)
            .setUserState(userState)
        val response = CommonMsg.LoginResponse.newBuilder()
            .setUserInfo(userInfo)
            .setTimestamp(now)
        val netResponse = NetMessage(Rpc.RpcNameEnum.Login_VALUE, response).apply {
            userId = playerId
            sessionId = command.request.sessionId
        }
        command.gateActor.tell(netResponse, self())
        command.accountActor.tell(
            LocalMessage(
                InternalMessageId.PLAYER_LOGIN_RESULT,
                PlayerLoginResult(true, playerId, self()),
            ),
            self(),
        )
        log.info { "player login: playerId=$playerId accountId=$accountId firstLogin=$firstLogin" }
    }

    /** 每条玩家消息先心跳，再执行业务；没有独立 Actor 定时器。 */
    private suspend fun onGateMessage(msg: NetMessage, sender: ActorRef?) {
        val current = state ?: return
        heartbeat(current)
        when (msg.msgId) {
            Rpc.RpcNameEnum.Heartbeat_VALUE -> {
                val request = msg.getProto<CommonMsg.HeartbeatRequest>()
                val response = CommonMsg.HeartbeatResponse.newBuilder()
                    .setClientTimestamp(request?.clientTimestamp ?: 0)
                    .setServerTimestamp(System.currentTimeMillis())
                sender?.tell(NetMessage(msg.msgId, response).apply { userId = playerId }, self())
            }
            else -> sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.ClientError_VALUE), self())
        }
    }

    private suspend fun heartbeat(current: PlayerActorState) {
        val now = System.currentTimeMillis()
        if (now < current.nextMinuteTimestamp) return

        current.nextMinuteTimestamp = PlayerHeartbeatService.nextMinuteTimestamp(now)
        if (now - current.lastSuccessfulSaveTimestamp >= PlayerHeartbeatService.SAVE_COOLDOWN_MILLIS) {
            save(current)
        }
    }

    private suspend fun save(current: PlayerActorState): Boolean {
        return try {
            if (Db.dbPlayer.replace(current.dbPlayer)) {
                current.lastSuccessfulSaveTimestamp = System.currentTimeMillis()
                true
            } else {
                log.error { "save DbPlayer not acknowledged: playerId=$playerId" }
                false
            }
        } catch (error: Exception) {
            // 只在 Mongo ACK 后推进成功时间；失败后下一次 minute heartbeat 会继续重试。
            log.error(error) { "save DbPlayer failed: playerId=$playerId" }
            false
        }
    }

    private suspend fun forceSaveWithRetry(): Boolean {
        val current = state ?: return true
        repeat(3) { attempt ->
            if (save(current)) return true
            delay(100L * (attempt + 1))
        }
        log.error { "force save DbPlayer exhausted retries: playerId=$playerId" }
        return false
    }

    private suspend fun logout() {
        val current = state ?: run {
            context().stop(self())
            return
        }
        current.dbPlayer.account.lastLogoutTimestamp = Date()
        forceSaveWithRetry()
        notifyGlobalOffline()
        state = null
        context().stop(self())
    }

    private suspend fun notifyGlobalOnline(playerName: String) {
        val request = RemoteServer.GlobalPlayerOnlineRequest.newBuilder()
            .setPlayerId(playerId)
            .setPlayerName(playerName)
        globalActor()?.tell(
            RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalPlayerOnline_VALUE, request),
            self(),
        )
    }

    private suspend fun notifyGlobalOffline() {
        val request = RemoteServer.GlobalPlayerOfflineRequest.newBuilder().setPlayerId(playerId)
        globalActor()?.tell(
            RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalPlayerOffline_VALUE, request),
            self(),
        )
    }

    private suspend fun globalActor(): ActorRef? {
        val globalId = NacosService.getNodeInfoListByNodeKind(NodeKind.global).minOfOrNull { it.nodeId }
            ?: return null
        return NacosService.getActorRefByNodeKindAndNodeId(NodeKind.global, globalId)
    }

    /** 重登录时直接从 Global 的 BattleManagerActorState 恢复当前战局状态。 */
    private suspend fun queryGlobalPlayerState(): CommonMsg.UserState? {
        val global = globalActor() ?: return null
        val request = RemoteServer.GlobalPlayerStateQueryRequest.newBuilder().setPlayerId(playerId)
        val reply = global.askAwait(
            RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcGlobalPlayerStateQuery_VALUE, request)
        ) as? RemoteMessage ?: return null
        return reply.getProto<RemoteServer.GlobalPlayerStateQueryResponse>()?.userState
    }
}
