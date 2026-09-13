package com.jacey.game.gateway.service

import akka.actor.ActorRef
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.db.service.BattleInfoService
import com.jacey.game.gateway.session.SessionManagerService
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 消息路由（object 单例，原 gateway MessageManager）
 *
 * 原通过 Mongo *LoadBalance 表查 akkaPath 转发，现由 Nacos naming 路由（NodeRegister）。
 */
object MessageRouterService {
    private val logger = KotlinLogging.logger {}

    /** 是否可服务客户端：logic 在线且主 logic 已就绪（均来自 Nacos 目录） */
    fun isAvailableForClient(): Boolean {
        return mainLogicServerId() > 0
    }

    /** 主 logic 服务器 id：取 Nacos metadata 标记 isMainLogicServer 的节点 */
    fun mainLogicServerId(): Int {
        return NacosService.getNodeInfoListByNodeKind(NodeKind.logic)
            .firstOrNull { it.isMainLogicServer }?.nodeId ?: 0
    }

    suspend fun forwardToLogic(msg: NetMessage, sender: ActorRef?): Boolean {
        val ref = NacosService.getRandomActorRefByNodeKind(NodeKind.logic) ?: run {
            logger.error { "【转发失败】logic 不可用 rpcNum=${msg.msgId}" }
            return false
        }
        logger.info { "【转发 logic】rpcNum=${msg.msgId} sender=${sender?.path() ?: "noSender"} -> ${ref.path()}" }
        ref.tell(msg, sender)
        return true
    }

    suspend fun forwardToMainLogic(msg: NetMessage, sender: ActorRef?): Boolean {
        val mainId = mainLogicServerId()
        val ref = if (mainId > 0) {
            NacosService.getActorRefByNodeKindAndNodeId(NodeKind.logic, mainId)
        } else {
            null
        } ?: NacosService.getRandomActorRefByNodeKind(NodeKind.logic)

        if (ref == null) {
            logger.error { "【转发失败】mainLogic 不可用 rpcNum=${msg.msgId} mainId=$mainId" }
            return false
        }
        logger.info { "【转发 mainLogic】rpcNum=${msg.msgId} mainId=$mainId sender=${sender?.path() ?: "noSender"} -> ${ref.path()}" }
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
            NacosService.getActorRefByNodeKindAndNodeId(NodeKind.battle, it)
        } ?: NacosService.getRandomActorRefByNodeKind(NodeKind.battle)

        return if (ref != null) {
            ref.tell(msg, sender)
            true
        } else {
            false
        }
    }

    suspend fun forwardToChat(msg: NetMessage, sender: ActorRef?): Boolean {
        val userId = msg.userId
        val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
        val chatServerId = battleId?.let { BattleInfoService.getOneBattleIdToChatServerId(it) }
        val ref = chatServerId?.let { NacosService.getActorRefByNodeKindAndNodeId(NodeKind.chat, chatServerId) }
        return if (ref != null) {
            ref.tell(msg, sender)
            true
        } else false
    }

    /** 强制下线推送（logic 通知 gateway 用） */
    fun forceOffline(sessionId: Int, reason: CommonEnum.ForceOfflineReasonEnum) {
        val channel = SessionManagerService.channelOf(sessionId) ?: run {
            logger.error { "forceOffline: channel not found, sessionId=$sessionId" }
            return
        }
        val push = CommonMsg.ForceOfflinePush.newBuilder()
            .setForceOfflineReason(reason)
            .build()
        val session = SessionManagerService.sessionOf(channel)
        session?.write(NetMessage(20001, push))
        channel.close()
    }
}
