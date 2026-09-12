package com.jacey.game.gateway.actor

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.constants.GlobalConstant
import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.common.framework.process.Dispatcher
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.LocalServer
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.db.service.BattleInfoService
import com.jacey.game.gateway.MessageRouter
import com.jacey.game.gateway.Session
import com.jacey.game.gateway.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 网关主 Actor（协程化）
 * - GM 注册（5 秒重试直到成功）
 * - 客户端 NetMessage 路由分发（注册/登录 → logic，对战 → battle，聊天 → chat）
 * - 强制下线推送处理
 */
class GatewayNodeActor : BaseMessageActor() {
    private var reconnectJob: Job? = null

    init {
        registerHandler(LocalMessage::class.java) { msg, _ -> onLocal(msg) }
        registerHandler(RemoteMessage::class.java) { msg, _ -> onRemote(msg) }
        registerHandler(NetMessage::class.java) { msg, _ -> onNet(msg) }
    }

    override suspend fun onTerminated(t: akka.actor.Terminated) {
        MessageRouter.isConnectedToGm = false
        logger.warn { "GM connection lost, restarting registration task (5s)" }
        startReconnect()
    }

    private suspend fun onLocal(msg: LocalMessage) {
        when (msg.rpcNum) {
            LocalServer.LocalRpcNameEnum.LocalRpcRegistToGmServer_VALUE -> registerToGm()
        }
    }

    private suspend fun onRemote(msg: RemoteMessage) {
        when (msg.rpcNum) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcRegistServer_VALUE -> {
                if (msg.errorCode == RemoteServer.RemoteRpcErrorCodeEnum.RemoteRpcOk_VALUE) {
                    MessageRouter.isConnectedToGm = true
                    logger.info { "【向GM服务器注册成功....】" }
                    stopReconnect()
                } else {
                    logger.error { "【GM服务器注册失败】errorCode=${msg.errorCode}" }
                    com.jacey.game.common.framework.process.Exit.exit(0)
                }
            }
            RemoteServer.RemoteRpcNameEnum.RemoteRpcLogicServerNoticeGatewayForceOfflineClient_VALUE -> {
                val push = msg.getProto<RemoteServer.LogicServerNoticeGatewayForceOfflineClientPush>()
                if (push != null) {
                    MessageRouter.forceOffline(
                        push.sessionId,
                        CommonEnum.ForceOfflineReasonEnum.ForceOfflineSameUserLogin
                    )
                }
            }
        }
    }

    private suspend fun onNet(msg: NetMessage) {
        // 网关本身不处理客户端 NetMessage（由 ClientSessionActor 处理）；此为兜底
        logger.warn { "GatewayActor got unexpected NetMessage rpcNum=${msg.rpcNum}" }
    }

    private suspend fun registerToGm() {
        logger.info { "【正在尝试连接GM服务器....】" }
        val serverInfo = RemoteServer.RemoteServerInfo.newBuilder()
            .setServerType(CommonEnum.RemoteServerTypeEnum.ServerTypeGateway)
            .setServerId(NodeRegister.selfId)
            .setAkkaPath(NodeRegister.selfInfo.actorPath)
            .setGatewayConnectPath(AppConfig.instance.gatewayConnectPath)
        val request = RemoteServer.RegistServerRequest.newBuilder()
            .setServerInfo(serverInfo)
        MessageRouter.sendRemoteToGm(
            RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcRegistServer_VALUE, request),
            self()
        )
    }

    private fun startReconnect() {
        if (reconnectJob == null) {
            val scope = CoroutineScope(com.jacey.game.common.framework.process.Dispatcher.Scheduler)
            val msg: com.jacey.game.common.msg.IMessage =
                LocalMessage(LocalServer.LocalRpcNameEnum.LocalRpcRegistToGmServer_VALUE)
            reconnectJob = scope.launch {
                kotlinx.coroutines.delay(0)
                while (isActive) {
                    self().tell(msg, ActorRef.noSender())
                    kotlinx.coroutines.delay(5000)
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
