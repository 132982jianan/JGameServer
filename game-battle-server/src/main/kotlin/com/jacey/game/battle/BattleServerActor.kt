package com.jacey.game.battle

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.LocalServer
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.process.Dispatcher
import com.jacey.game.common.msg.IMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 对战服主 Actor（原 BattleServerActor）
 * - GM 注册（5 秒重试）
 * - 客户端离线通知处理
 * - 客户端 NetMessage 交给 BattleRoomManagerActor
 */
class BattleServerActor : BaseMessageActor() {
    private var reconnectJob: Job? = null

    init {
        registerHandler(LocalMessage::class.java) { msg, _ -> onLocal(msg) }
        registerHandler(RemoteMessage::class.java) { msg, sender -> onRemote(msg, sender) }
        registerHandler(NetMessage::class.java) { msg, sender ->
            // 客户端对战请求交给房间管理
            BattleRooms.proxyNetMessage(msg, sender)
        }
    }

    override suspend fun onTerminated(terminated: akka.actor.Terminated) {
        MessageRouterB.isConnectedToGm = false
        logger.warn { "【GM服务器连接已断开...】, 开始重连任务, 执行间隔 = 5s" }
        startReconnect()
    }

    private suspend fun onLocal(msg: LocalMessage) {
        when (msg.msgId) {
            LocalServer.LocalRpcNameEnum.LocalRpcRegistToGmServer_VALUE -> registerToGm()
        }
    }

    private suspend fun onRemote(msg: RemoteMessage, sender: ActorRef?) {
        when (msg.msgId) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcRegistServer_VALUE -> {
                if (msg.errorCode == RemoteServer.RemoteRpcErrorCodeEnum.RemoteRpcOk_VALUE) {
                    MessageRouterB.isConnectedToGm = true
                    logger.info { "【向GM服务器注册成功....】" }
                    stopReconnect()
                } else {
                    logger.error { "【GM服务器注册失败】errorCode=${msg.errorCode}" }
                    com.jacey.game.common.framework.process.Exit.exit(0)
                }
            }
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

    private suspend fun registerToGm() {
        logger.info { "【正在尝试连接GM服务器....】" }
        val serverInfo = RemoteServer.RemoteServerInfo.newBuilder()
            .setServerType(CommonEnum.RemoteServerTypeEnum.ServerTypeBattle)
            .setServerId(NacosService.selfId)
            .setAkkaPath(NacosService.selfInfo.actorPath)
        val request = RemoteServer.RegistServerRequest.newBuilder()
            .setServerInfo(serverInfo)
        val gmRef = NacosService.getActorRefByNodeKindAndNodeId(NodeKind.gm, 1)
        gmRef?.tell(RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcRegistServer_VALUE, request), self())
    }

    private fun startReconnect() {
        if (reconnectJob == null) {
            val scope = CoroutineScope(Dispatcher.Scheduler)
            val msg: IMessage =
                LocalMessage(LocalServer.LocalRpcNameEnum.LocalRpcRegistToGmServer_VALUE)
            reconnectJob = scope.launch {
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
