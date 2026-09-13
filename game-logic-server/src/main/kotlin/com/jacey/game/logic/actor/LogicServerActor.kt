package com.jacey.game.logic.actor

import akka.actor.ActorRef
import akka.actor.Terminated
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.common.framework.process.Dispatcher
import com.jacey.game.common.framework.process.Exit
import com.jacey.game.common.msg.IMessage
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.LocalServer
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.PlayStateService
import com.jacey.game.logic.service.ActorRefManagerService
import com.jacey.game.logic.service.MatchService
import com.jacey.game.logic.service.MessageRouterService
import com.jacey.game.logic.service.OnlineClientService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 逻辑服主 Actor（原 LogicServerActor）
 * - GM 注册（5 秒重试）
 * - 客户端离线通知处理（匹配中取消、会话移除）
 */
class LogicServerActor : BaseMessageActor() {
    private var reconnectJob: Job? = null

    init {
        registerHandler(LocalMessage::class.java) { msg, _ -> onLocal(msg) }
        registerHandler(RemoteMessage::class.java) { msg, _ -> onRemote(msg) }
        registerHandler(NetMessage::class.java) { msg, sender ->
            // 顶层分发：按 rpcNum 投递给业务子 actor（原 MessageManager.handleRequest）
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
                    "【分发】rpcNum=${msg.msgId} -> ${
                        targetActorRef.path().name()
                    } sender=${sender?.path() ?: "noSender"}"
                }

                // 分发过去
                targetActorRef.tell(msg, sender)
            } else {
                logger.error { "【分发失败】无业务 actor 处理 rpcNum=${msg.msgId}" }
                sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.ServerError_VALUE), self())
            }
        }
    }

    override suspend fun onTerminated(terminated: Terminated) {
        MessageRouterService.isConnectedToGm = false
        logger.warn { "【GM服务器连接已断开...】, 开始重连任务, 执行间隔 = 5s" }
        startReconnect()
    }

    private suspend fun onLocal(msg: LocalMessage) {
        when (msg.msgId) {
            LocalServer.LocalRpcNameEnum.LocalRpcRegistToGmServer_VALUE -> registerToGm()
        }
    }

    private suspend fun onRemote(msg: RemoteMessage) {
        when (msg.msgId) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcRegistServer_VALUE -> {
                if (msg.errorCode == RemoteServer.RemoteRpcErrorCodeEnum.RemoteRpcOk_VALUE) {
                    MessageRouterService.isConnectedToGm = true
                    logger.info { "【向GM服务器注册成功....】" }
                    stopReconnect()
                } else {
                    logger.error { "【GM服务器注册失败】errorCode=${msg.errorCode}" }
                    Exit.exit(0)
                }
            }

            RemoteServer.RemoteRpcNameEnum.RemoteRpcGatewayNoticeClientOfflinePush_VALUE -> {
                val push = msg.getProto<RemoteServer.GatewayNoticeClientOfflinePush>() ?: return
                if (push.userId != 0 && push.isUserOffline) {
                    // 只有主逻辑服务器处理匹配取消
                    if (AppConfig.Companion.instance.isMainLogicServer) {
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

    private suspend fun registerToGm() {
        logger.info { "【正在尝试连接GM服务器....】" }
        val serverInfo = RemoteServer.RemoteServerInfo.newBuilder()
            .setServerType(CommonEnum.RemoteServerTypeEnum.ServerTypeLogic)
            .setServerId(NodeRegister.selfId)
            .setAkkaPath(NodeRegister.selfInfo.actorPath)
            .setIsMainLogicServer(AppConfig.Companion.instance.isMainLogicServer)
        val request = RemoteServer.RegistServerRequest.newBuilder()
            .setServerInfo(serverInfo)
        MessageRouterService.sendRemoteToGm(
            RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcRegistServer_VALUE, request),
            self()
        )
    }

    private fun startReconnect() {
        if (reconnectJob == null) {
            val scope = CoroutineScope(Dispatcher.Scheduler)
            val msg: IMessage =
                LocalMessage(LocalServer.LocalRpcNameEnum.LocalRpcRegistToGmServer_VALUE)
            reconnectJob = scope.launch {
                while (isActive) {
                    self().tell(msg, ActorRef.noSender())
                    delay(5000)
                }
            }
        }
    }

    private fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    override fun preStart() {
        super.preStart()
        startReconnect()
    }
}