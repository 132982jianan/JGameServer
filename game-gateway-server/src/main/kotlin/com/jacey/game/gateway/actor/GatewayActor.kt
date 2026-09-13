package com.jacey.game.gateway.actor

import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.gateway.service.MessageRouterService

/**
 * 网关主 Actor
 * - 强制下线推送处理（logic 顶号通知）
 * - 节点发现/存活由 Nacos 维护，无 GM 注册流程
 */
class GatewayNodeActor : BaseMessageActor() {

    init {
        registerHandler(RemoteMessage::class.java) { msg, _ -> onRemote(msg) }
        registerHandler(NetMessage::class.java) { msg, _ -> onNet(msg) }
    }

    private suspend fun onRemote(msg: RemoteMessage) {
        when (msg.msgId) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcLogicServerNoticeGatewayForceOfflineClient_VALUE -> {
                val push = msg.getProto<RemoteServer.LogicServerNoticeGatewayForceOfflineClientPush>()
                if (push != null) {
                    MessageRouterService.forceOffline(
                        push.sessionId,
                        CommonEnum.ForceOfflineReasonEnum.ForceOfflineSameUserLogin
                    )
                }
            }
        }
    }

    private suspend fun onNet(msg: NetMessage) {
        // 网关本身不处理客户端 NetMessage（由 ClientSessionActor 处理）；此为兜底
        logger.warn { "GatewayActor got unexpected NetMessage msgId=${msg.msgId}" }
    }
}
