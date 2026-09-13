package com.jacey.game.gui.netty

import com.jacey.game.common.msg.NetMessage
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToByteEncoder

class ProtocolEncoder : MessageToByteEncoder<NetMessage>() {
    override fun encode(ctx: ChannelHandlerContext, msg: NetMessage, out: ByteBuf) {
        out.writeBytes(msg.toByteBuf())
    }
}