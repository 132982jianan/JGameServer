package com.jacey.game.gui

import com.jacey.game.common.framework.net.WebSocketProtocol
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.gui.config.GuiConfig
import com.jacey.game.gui.service.MessageRouterService
import com.jacey.game.gui.service.NetService
import com.sun.net.httpserver.HttpServer
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSocketConnectionTest {
    @Test
    fun `GUI discovers websocket endpoint completes handshake and exchanges binary messages`() {
        val wireMessage = CompletableFuture<ByteArray>()
        val response = CompletableFuture<NetMessage>()
        MessageRouterService.register(Rpc.RpcNameEnum.Heartbeat_VALUE) { response.complete(it) }
        withGate({ ch ->
            ch.pipeline().addLast(WebSocketServerProtocolHandler(WebSocketProtocol.PATH))
            ch.pipeline().addLast(object : SimpleChannelInboundHandler<BinaryWebSocketFrame>() {
                override fun channelRead0(ctx: ChannelHandlerContext, msg: BinaryWebSocketFrame) {
                    val bytes = ByteArray(msg.content().readableBytes())
                    msg.content().getBytes(msg.content().readerIndex(), bytes)
                    wireMessage.complete(bytes)
                    ctx.writeAndFlush(msg.retainedDuplicate())
                }
            })
        }) {
            runBlocking { NetService.connect() }
            assertTrue(NetService.isConnected)
            val payload = byteArrayOf(1, 2, 3)
            assertTrue(NetService.send(NetMessage(Rpc.RpcNameEnum.Heartbeat_VALUE, payload)))
            val wire = ByteBuffer.wrap(wireMessage.get(5, TimeUnit.SECONDS))
            assertEquals(NetMessage.HEADER_LENGTH + payload.size, wire.int)
            assertEquals(Rpc.RpcNameEnum.Heartbeat_VALUE, wire.int)
            assertEquals(Rpc.RpcErrorCodeEnum.Ok_VALUE, wire.int)
            val decoded = response.get(5, TimeUnit.SECONDS)
            assertEquals(Rpc.RpcNameEnum.Heartbeat_VALUE, decoded.msgId)
            assertContentEquals(payload, decoded.data)
            NetService.disconnect()
            assertFalse(NetService.isConnected)
        }
    }

    @Test
    fun `failed websocket handshake never marks GUI connected`() {
        withGate({ ch ->
            ch.pipeline().addLast(object : SimpleChannelInboundHandler<FullHttpRequest>() {
                override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
                    ctx.writeAndFlush(DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST))
                        .addListener(io.netty.channel.ChannelFutureListener.CLOSE)
                }
            })
        }) {
            val failure = runCatching { runBlocking { NetService.connect() } }.exceptionOrNull()
            assertTrue(failure != null)
            assertFalse(NetService.isConnected)
        }
    }

    private fun withGate(configure: (SocketChannel) -> Unit, test: () -> Unit) {
        val group = NioEventLoopGroup(1)
        val gate = ServerBootstrap().group(group).channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(HttpServerCodec(), HttpObjectAggregator(WebSocketProtocol.MAX_FRAME_LENGTH))
                    configure(ch)
                }
            }).bind("127.0.0.1", 0).syncUninterruptibly().channel()
        val gatePort = (gate.localAddress() as InetSocketAddress).port
        val portal = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        portal.createContext("/gate") { exchange ->
            // 只提供 WebSocket 地址，确保 GUI 不再依赖原生 socket 地址字段。
            val body = """{"gateId":1,"websocketEndpoint":"ws://127.0.0.1:$gatePort/websocket","protocolVersion":1}"""
                .toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val previousHost = GuiConfig.serverHost
        val previousPort = GuiConfig.serverPort
        try {
            portal.start()
            GuiConfig.serverHost = "127.0.0.1"
            GuiConfig.serverPort = portal.address.port
            test()
        } finally {
            NetService.disconnect()
            GuiConfig.serverHost = previousHost
            GuiConfig.serverPort = previousPort
            portal.stop(0)
            gate.close().syncUninterruptibly()
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly()
        }
    }
}
