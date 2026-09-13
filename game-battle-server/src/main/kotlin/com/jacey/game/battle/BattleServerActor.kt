package com.jacey.game.battle

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.RemoteServer

/**
 * 对战服主 Actor（原 BattleServerActor）
 * - 客户端对战请求交给房间管理（BattleRooms）
 * - 客户端离线通知、主逻辑服创建战场通知
 * - 节点发现/存活由 Nacos 维护，无 GM 注册流程
 */
class BattleServerActor : BaseMessageActor() {

    init {
        registerHandler(RemoteMessage::class.java) { msg, sender -> onRemote(msg, sender) }
        registerHandler(NetMessage::class.java) { msg, sender ->
            // 客户端对战请求交给房间管理
            BattleRooms.proxyNetMessage(msg, sender)
        }
    }

    private suspend fun onRemote(msg: RemoteMessage, sender: ActorRef?) {
        when (msg.msgId) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcGatewayNoticeClientOfflinePush_VALUE -> {
                val push = msg.getProto<RemoteServer.GatewayNoticeClientOfflinePush>()
                BattleRooms.removeGatewayResponseActor(push?.sessionId ?: 0)
            }
            RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeBattleServerCreateNewBattle_VALUE -> {
                val request = msg.getProto<RemoteServer.NoticeBattleServerCreateNewBattleRequest>()
                if (request != null) {
                    BattleRooms.createNewBattle(request, sender)
                }
            }
        }
    }
}
