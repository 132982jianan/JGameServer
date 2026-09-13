package com.jacey.game.logic.service

import akka.actor.ActorRef
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.akka.ClusterService
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.db.redis.SessionIdRedis
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 消息路由（object 单例，原 logic MessageManager）
 */
object MessageRouterService {
    private val logger = KotlinLogging.logger {}

    /** 推送 NetMessage 到指定 session 的客户端 */
    fun sendNetMsgToOneSession(sessionId: Int, netMsg: NetMessage): Boolean {
        val gatewayResponseActor = OnlineClientService.getGatewayResponseActor(sessionId)
        return if (gatewayResponseActor != null) {
            gatewayResponseActor.tell(netMsg, ActorRef.noSender())
            true
        } else false
    }

    /** 推送 NetMessage 到指定 userId 的客户端 */
    suspend fun sendNetMsgToOneUser(userId: Int, netMsg: NetMessage): Boolean {
        logger.info { "【推送消息】userId=$userId msgId=${netMsg.msgId}" }
        val sessionId = SessionIdRedis.getOneUserIdToSessionId(userId)
        return if (sessionId != null) {
            sendNetMsgToOneSession(sessionId, netMsg)
        } else false
    }

    /** 通知 battle 服务器创建新战场（随机负载均衡；askAwait 拿到创建结果再返回） */
    suspend fun noticeBattleServerCreateNewBattle(
        battleType: CommonEnum.BattleTypeEnum,
        battleId: String,
        userIds: List<Int>,
    ): Boolean {
        val battleRoomInfo = RemoteServer.BattleRoomInfo.newBuilder()
            .setBattleType(battleType)
            .setBattleId(battleId)
            .addAllUserIds(userIds)
        val request = RemoteServer.NoticeBattleServerCreateNewBattleRequest.newBuilder()
            .setBattleRoomInfo(battleRoomInfo)
        val reply = ClusterService.askRandomAwait(
            NodeKind.battle,
            RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeBattleServerCreateNewBattle_VALUE, request)
        )
        if (reply == null || reply.errorCode != RemoteServer.RemoteRpcErrorCodeEnum.RemoteRpcOk_VALUE) {
            logger.error { "【创建战场失败】battleId=$battleId errorCode=${reply?.errorCode}" }
            return false
        }
        return true
    }
}
