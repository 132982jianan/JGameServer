package com.jacey.game.common.msg

import io.netty.buffer.ByteBuf

/**
 * 消息载体接口：协议号 + 二进制化
 */
interface IMessage {
    val msgId: Int
    fun toBinaryMsg(): ByteBuf?
}