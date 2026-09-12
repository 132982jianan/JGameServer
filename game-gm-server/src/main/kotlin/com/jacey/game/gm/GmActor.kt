package com.jacey.game.gm

import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.RemoteServer
import akka.actor.ActorRef

/**
 * GM 主 Actor（原 GmActor）：处理各节点注册请求
 */
class GmActor : BaseMessageActor() {

    init {
        registerHandler(RemoteMessage::class.java) { msg, sender -> onRemote(msg, sender) }
    }

    private suspend fun onRemote(msg: RemoteMessage, sender: ActorRef?) {
        when (msg.rpcNum) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcRegistServer_VALUE -> {
                val request = msg.getProto<RemoteServer.RegistServerRequest>()
                if (request == null) {
                    sender?.tell(RemoteMessage(msg.rpcNum, RemoteServer.RemoteRpcErrorCodeEnum.RemoteRpcServerError_VALUE), self())
                    return
                }
                // watch 远端节点：断线自动移除注册
                // sender 为消息信封里的原始发送方（挂起后 context().sender() 已失效，不可用）
                val from = sender ?: return
                context().watch(from)
                val ok = GmRegistry.registServer(request, from, self())
                if (ok) {
                    val info = request.serverInfo
                    logger.info { "【服务器注册成功】type=${info.serverType} id=${info.serverId}" }
                    val respBuilder = RemoteServer.RegistServerResponse.newBuilder()
                    from.tell(
                        RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcRegistServer_VALUE, respBuilder),
                        self()
                    )
                } else {
                    logger.error { "【服务器注册失败】has registed, serverType=${request.serverInfo.serverType}" }
                    from.tell(
                        RemoteMessage(
                            RemoteServer.RemoteRpcNameEnum.RemoteRpcRegistServer_VALUE,
                            RemoteServer.RemoteRpcErrorCodeEnum.RemoteRpcRegistServerErrorHasRegisted_VALUE
                        ),
                        self()
                    )
                }
            }
            RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeExecuteGmCmd_VALUE -> {
                // 原 executeGmCmd TODO
            }
        }
    }

    override suspend fun onTerminated(t: akka.actor.Terminated) {
        GmRegistry.removeActor(t.actor)
        logger.info { "【节点下线移除】${t.actor.path()}" }
    }
}
