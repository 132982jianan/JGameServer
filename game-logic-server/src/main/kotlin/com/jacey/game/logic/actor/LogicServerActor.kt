package com.jacey.game.logic.actor

import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.PlayStateService
import com.jacey.game.logic.service.ActorRefManagerService
import com.jacey.game.logic.service.MatchService
import com.jacey.game.logic.service.OnlineClientService

/**
 * 逻辑服主 Actor（原 LogicServerActor）
 * - 顶层分发：按 rpcNum 投递给业务子 actor（原 MessageManager.handleRequest）
 * - 客户端离线通知处理（匹配中取消、会话移除）
 * - 节点发现/存活由 Nacos 维护，无 GM 注册流程
 */
class LogicServerActor : BaseMessageActor() {

    init {
        registerHandler(RemoteMessage::class.java) { msg, _ -> onRemote(msg) }
        registerHandler(NetMessage::class.java) { msg, sender -> dispatchNet(msg, sender) }
    }

    private suspend fun dispatchNet(msg: NetMessage, sender: akka.actor.ActorRef?) {
        val targetActorRef = when (msg.msgId) {
            Rpc.RpcNameEnum.Regist_VALUE -> ActorRefManagerService.registActor
            Rpc.RpcNameEnum.Login_VALUE -> ActorRefManagerService.loginActor
            Rpc.RpcNameEnum.Match_VALUE,
            Rpc.RpcNameEnum.CancelMatch_VALUE,
            Rpc.RpcNameEnum.ReadyToStartGame_VALUE -> ActorRefManagerService.matchActor

            else -> null
        }

        if (targetActorRef != null) {
            logger.info {
                "【分发】rpcNum=${msg.msgId} -> ${targetActorRef.path().name()} sender=${sender?.path() ?: "noSender"}"
            }
            targetActorRef.tell(msg, sender)
        } else {
            logger.error { "【分发失败】无业务 actor 处理 rpcNum=${msg.msgId}" }
            sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.ServerError_VALUE), self())
        }
    }

    private suspend fun onRemote(msg: RemoteMessage) {
        when (msg.msgId) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcGatewayNoticeClientOfflinePush_VALUE -> {
                val push = msg.getProto<RemoteServer.GatewayNoticeClientOfflinePush>() ?: return
                if (push.userId != 0 && push.isUserOffline) {
                    // 只有主逻辑服务器处理匹配取消
                    if (AppConfig.instance.isMainLogicServer) {
                        val state = PlayStateService.getPlayStateByUserId(push.userId)
                        if (state != null &&
                            state.userActionState == CommonEnum.UserActionStateEnum.Matching_VALUE
                        ) {
                            MatchService.removeMatchPlayer(
                                push.userId,
                                CommonEnum.BattleTypeEnum.forNumber(state.battleType)
                            )
                        }
                    }
                }
                OnlineClientService.removeSessionIdToGatewayResponseActor(push.sessionId)
            }
        }
    }
}
