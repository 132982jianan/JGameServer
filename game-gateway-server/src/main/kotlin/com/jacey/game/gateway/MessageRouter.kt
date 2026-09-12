package com.jacey.game.gateway

import akka.actor.ActorRef
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.BattleInfoService
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 消息路由（object 单例，原 gateway MessageManager）
 *
 * 原通过 Mongo *LoadBalance 表查 akkaPath 转发，现由 Nacos naming 路由（NodeRegister）。
 */
object MessageRouter {
    private val logger = KotlinLogging.logger {}

    /** 是否已连接 GM（注册成功标记，由 GatewayActor 维护） */
    @Volatile
    var isConnectedToGm: Boolean = false

    fun isAvailableForClient(): Boolean {
        return isConnectedToGm
                && NodeRegister.nodesOf(NodeKind.logic).any { true }
                && mainLogicServerId() > 0
    }

    /** 主 logic 服务器 id：优先取注册时声明 isMainLogicServer 的节点（metadata 标记） */
    fun mainLogicServerId(): Int {
        val nodes = NodeRegister.nodesOf(NodeKind.logic)
        return nodes
            .firstOrNull {
                it.nodeId > 0
            }?.nodeId ?: 0
    }

    suspend fun forwardToLogic(msg: NetMessage, sender: ActorRef?): Boolean {
        val ref = NodeRegister.randomActorRefOf(NodeKind.logic) ?: run {
            KotlinLogging.logger {}.error { "【转发失败】logic 不可用 rpcNum=${msg.rpcNum}" }
            return false
        }
        KotlinLogging.logger {}.info { "【转发 logic】rpcNum=${msg.rpcNum} sender=${sender?.path() ?: "noSender"} -> ${ref.path()}" }
        ref.tell(msg, sender)
        return true
    }

    suspend fun forwardToMainLogic(msg: NetMessage, sender: ActorRef?): Boolean {
        val logger = KotlinLogging.logger {}
        val mainId = mainLogicServerId()
        val ref = if (mainId > 0) NodeRegister.actorRefOf(NodeKind.logic, mainId) else null
            ?: NodeRegister.randomActorRefOf(NodeKind.logic)
        if (ref == null) {
            logger.error { "【转发失败】mainLogic 不可用 rpcNum=${msg.rpcNum} mainId=$mainId" }
            return false
        }
        logger.info { "【转发 mainLogic】rpcNum=${msg.rpcNum} mainId=$mainId sender=${sender?.path() ?: "noSender"} -> ${ref.path()}" }
        ref.tell(msg, sender)
        return true
    }

    suspend fun forwardToBattle(msg: NetMessage, sender: ActorRef?): Boolean {
        val userId = msg.userId
        val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
        val battleServerId = battleId?.let {
            BattleInfoService.getOneBattleIdToBattleServerId(it)
        }
        val ref = battleServerId?.let {
            NodeRegister.actorRefOf(NodeKind.battle, it)
        } ?: NodeRegister.randomActorRefOf(NodeKind.battle)

        return if (ref != null) {
            ref.tell(msg, sender); true
        } else {
            false
        }
    }

    suspend fun forwardToChat(msg: NetMessage, sender: ActorRef?): Boolean {
        val userId = msg.userId
        val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
        val chatServerId = battleId?.let { BattleInfoService.getOneBattleIdToChatServerId(it) }
        val ref = chatServerId?.let { NodeRegister.actorRefOf(NodeKind.chat, chatServerId) }
        return if (ref != null) {
            ref.tell(msg, sender); true
        } else false
    }

    /** 强制下线推送（logic 通知 gateway 用） */
    fun forceOffline(sessionId: Int, reason: CommonEnum.ForceOfflineReasonEnum) {
        val channel = SessionManager.channelOf(sessionId) ?: run {
            logger.error { "forceOffline: channel not found, sessionId=$sessionId" }
            return
        }
        val push = CommonMsg.ForceOfflinePush.newBuilder()
            .setForceOfflineReason(reason)
            .build()
        val session = SessionManager.sessionOf(channel)
        session?.write(NetMessage(20001, push))
        channel.close()
    }

    suspend fun sendRemoteToGm(msg: RemoteMessage, sender: ActorRef?) {
        val ref = NodeRegister.actorRefOf(NodeKind.gm, 1)
        if (ref != null) {
            ref.tell(msg, sender)
        } else {
            logger.error { "gm actor not found" }
        }
    }

    suspend fun sendRemoteToGateway(msg: RemoteMessage, gatewayId: Int): Boolean {
        val ref = NodeRegister.actorRefOf(NodeKind.gateway, gatewayId) ?: return false
        ref.tell(msg, null)
        return true
    }

    suspend fun sendRemoteToLogic(msg: RemoteMessage, logicServerId: Int): Boolean {
        val ref = NodeRegister.actorRefOf(NodeKind.logic, logicServerId) ?: return false
        ref.tell(msg, null)
        return true
    }

    suspend fun sendRemoteToBattle(msg: RemoteMessage, battleServerId: Int): Boolean {
        val ref = NodeRegister.actorRefOf(NodeKind.battle, battleServerId) ?: return false
        ref.tell(msg, null)
        return true
    }

    suspend fun sendRemoteToChat(msg: RemoteMessage, chatServerId: Int): Boolean {
        val ref = NodeRegister.actorRefOf(NodeKind.chat, chatServerId) ?: return false
        ref.tell(msg, null)
        return true
    }
}
