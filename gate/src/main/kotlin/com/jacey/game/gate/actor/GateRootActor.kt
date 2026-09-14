package com.jacey.game.gate.actor

import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage

/** Nacos 暴露的 Gate 根 Actor；连接级业务由每条连接自己的 GateActor 承担。 */
class GateRootActor : BaseMessageActor() {
    init {
        registerHandler(RemoteMessage::class.java) { msg, _ ->
            logger.warn { "GateRootActor got unsupported remote msgId=${msg.msgId}" }
        }
        registerHandler(NetMessage::class.java) { msg, _ ->
            logger.warn { "GateRootActor got unexpected net msgId=${msg.msgId}" }
        }
    }
}
