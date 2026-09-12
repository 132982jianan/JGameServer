package com.jacey.game.gui.netty

import com.jacey.game.common.msg.NetMessage
import com.jacey.game.gui.service.MessageRouterService
import com.jacey.game.gui.service.NetService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.CharsetUtil

class ClientHandler : ChannelInboundHandlerAdapter() {
    private val logger = KotlinLogging.logger {}

    private val HEARTBEAT = Unpooled.unreleasableBuffer(
        Unpooled.copiedBuffer("hb_request", CharsetUtil.UTF_8)
    )

    override fun channelActive(ctx: ChannelHandlerContext) {
        logger.info { "已连接服务器......" }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        logger.warn { "与服务器已断开......" }
        NetService.channel = null
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is NetMessage) {
            MessageRouterService.dispatch(msg)
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent && evt.state() == IdleState.WRITER_IDLE) {
            ctx.writeAndFlush(HEARTBEAT.duplicate())
            logger.info { "【心跳】发送心跳包结束...." }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
        val msg = cause.message ?: ""
        if (!msg.contains("远程主机强迫关闭")) {
            logger.error(cause) { "【链接异常断开】" }
        }
    }
}