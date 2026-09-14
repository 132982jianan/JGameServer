package com.jacey.game.gate.actor

import com.jacey.game.common.msg.AbstractMessage
import com.jacey.game.common.msg.NetMessage

/** Netty 入站消息；与业务节点回给 GateActor 的 NetMessage 明确区分。 */
class GateClientMsg(val netMessage: NetMessage) : AbstractMessage() {
    init {
        msgId = netMessage.msgId
    }
}
