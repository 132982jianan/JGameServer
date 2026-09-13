package com.jacey.game.gm

import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.RemoteServer

/**
 * GM 主 Actor（原 GmActor）
 * - GM 命令下发（原 executeGmCmd TODO 保持）
 * - 节点发现/存活由 Nacos 维护，无服务器注册流程
 */
class GmActor : BaseMessageActor() {

    init {
        registerHandler(RemoteMessage::class.java) { msg, _ -> onRemote(msg) }
    }

    private suspend fun onRemote(msg: RemoteMessage) {
        when (msg.msgId) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeExecuteGmCmd_VALUE -> {
                // 原 executeGmCmd TODO
            }
        }
    }
}
