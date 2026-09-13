package com.jacey.game.common.msg

import io.netty.buffer.ByteBuf

/** 抽象基类：公共 rpcNum 字段 */
abstract class AbstractMessage : IMessage {
    override var msgId: Int = 0
    /** 附加对象（本地消息可携带 protobuf Builder/对象，远程消息走 data） */
    var lite: Any? = null
    override fun toBinaryMsg(): ByteBuf? = null
}