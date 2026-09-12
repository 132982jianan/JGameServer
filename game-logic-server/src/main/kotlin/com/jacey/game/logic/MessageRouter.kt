package com.jacey.game.logic

import akka.actor.ActorRef
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.BattleInfoService
import com.jacey.game.db.redis.SessionIdRedis
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 会话索引（object 单例，原 logic OnlineClientManager）
 * sessionId -> gateway ResponseActor（远端响应直接转发给客户端）
 */
object OnlineClients {
    private val sessionIdToGatewayResponseActor =
        java.util.concurrent.ConcurrentHashMap<Int, ActorRef>()

    val onlineCount: Int get() = sessionIdToGatewayResponseActor.size

    fun addSessionIdToGatewayResponseActor(sessionId: Int, actor: ActorRef?) {
        if (actor != null) {
            sessionIdToGatewayResponseActor[sessionId] = actor
        }
    }

    fun removeSessionIdToGatewayResponseActor(sessionId: Int) {
        sessionIdToGatewayResponseActor.remove(sessionId)
    }

    fun getGatewayResponseActor(sessionId: Int): ActorRef? = sessionIdToGatewayResponseActor[sessionId]
}

/**
 * 消息路由（object 单例，原 logic MessageManager）
 */
object MessageRouter {
    private val logger = KotlinLogging.logger {}
    private var logicServerActorRef: ActorRef? = null

    /** logic 主 actor（注册响应 watch 用） */
    fun bindSelf(actor: ActorRef) {
        logicServerActorRef = actor
    }

    @Volatile
    var isConnectedToGm: Boolean = false

    fun isAvailableForUpdateLoadBalance(): Boolean = isConnectedToGm

    /** 推送 NetMessage 到指定 session 的客户端 */
    fun sendNetMsgToOneSession(sessionId: Int, netMsg: NetMessage): Boolean {
        val gatewayResponseActor = OnlineClients.getGatewayResponseActor(sessionId)
        return if (gatewayResponseActor != null) {
            gatewayResponseActor.tell(netMsg, ActorRef.noSender())
            true
        } else false
    }

    /** 推送 NetMessage 到指定 userId 的客户端 */
    suspend fun sendNetMsgToOneUser(userId: Int, netMsg: NetMessage): Boolean {
        logger.info { "【推送消息】userId=$userId rpcNum=${netMsg.rpcNum}" }
        val sessionId = SessionIdRedis.getOneUserIdToSessionId(userId)
        return if (sessionId != null) {
            sendNetMsgToOneSession(sessionId, netMsg)
        } else false
    }

    /** 通知 battle 服务器创建新战场（找最空闲的 battle 服务器） */
    suspend fun noticeBattleServerCreateNewBattle(
        battleType: CommonEnum.BattleTypeEnum,
        battleId: String,
        userIds: List<Int>,
        sender: ActorRef?
    ): Boolean {
        val ref = NodeRegister.randomActorRefOf(NodeKind.battle)
        if (ref != null) {
            val battleRoomInfo = RemoteServer.BattleRoomInfo.newBuilder()
                .setBattleType(battleType)
                .setBattleId(battleId)
                .addAllUserIds(userIds)
            val builder = RemoteServer.NoticeBattleServerCreateNewBattleRequest.newBuilder()
                .setBattleRoomInfo(battleRoomInfo)
            val remoteMsg = RemoteMessage(
                RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeBattleServerCreateNewBattle_VALUE,
                builder
            )
            ref.tell(remoteMsg, sender)
            return true
        }
        logger.error { "【创建战场失败】无可用 battle 服务器" }
        return false
    }

    suspend fun sendRemoteToGateway(msg: RemoteMessage, gatewayId: Int): Boolean {
        val ref = NodeRegister.actorRefOf(NodeKind.gateway, gatewayId) ?: return false
        ref.tell(msg, null)
        return true
    }

    suspend fun sendRemoteToGm(msg: RemoteMessage, sender: ActorRef?) {
        val ref = NodeRegister.actorRefOf(NodeKind.gm, 1)
        ref?.tell(msg, sender) ?: logger.error { "gm actor not found" }
    }
}
