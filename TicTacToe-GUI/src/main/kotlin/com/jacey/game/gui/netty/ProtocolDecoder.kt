package com.jacey.game.gui.netty

import com.jacey.game.common.msg.NetMessage
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder

class ProtocolDecoder : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        val readable = buf.readableBytes()
        if (readable < NettyConfig.HEADER_LENGTH) return
        buf.markReaderIndex()
        val totalLength = buf.readInt()
        if (readable >= totalLength) {
            val msgId = buf.readInt()
            val errorCode = buf.readInt()
            val bytes = ByteArray(totalLength - NettyConfig.HEADER_LENGTH)
            buf.readBytes(bytes)
            val message = NetMessage(msgId, bytes)
            message.errorCode = errorCode
            out.add(message)
        } else {
            buf.resetReaderIndex()
        }
    }
}