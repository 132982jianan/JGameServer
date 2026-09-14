package com.jacey.game.common.akka

import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage

/** HTTP-only 节点仍创建 Nacos 所声明的固定根 Actor，便于健康检查和一致寻址。 */
class NoopNodeActor : BaseMessageActor() {
    init {
        registerHandler(NetMessage::class.java) { msg, _ ->
            logger.warn { "HTTP-only node got unexpected net msgId=${msg.msgId}" }
        }
        registerHandler(RemoteMessage::class.java) { msg, _ ->
            logger.warn { "HTTP-only node got unexpected remote msgId=${msg.msgId}" }
        }
    }
}
