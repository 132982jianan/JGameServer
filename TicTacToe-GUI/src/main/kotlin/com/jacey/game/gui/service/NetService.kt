package com.jacey.game.gui.service

import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.framework.net.WebSocketNetMessageCodec
import com.jacey.game.common.framework.net.WebSocketProtocol
import com.jacey.game.gui.config.GuiConfig
import com.jacey.game.gui.netty.ClientHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.timeout.IdleStateHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 服务器连接管理（object 单例，原 ServerNodeManager + NettySocketServer + OnlineClientManager）
 *
 * 启动流程：
 * 1. HTTP GET http://portalHost:portalPort/gate 获取 Gate endpoint
 * 2. Netty WebSocket 握手成功后连接 Gate，使用二进制帧收发游戏消息
 */
object NetService {
    private val logger = KotlinLogging.logger {}

    @Volatile
    var channel: Channel? = null
        private set
    val isConnected: Boolean get() = channel?.isActive == true

    private var workerGroup: NioEventLoopGroup? = null

    /**
     * 连接 Gate（挂起）：Portal HTTP 取地址 → WebSocket 握手完成。
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        val url = URL("http://${GuiConfig.serverHost}:${GuiConfig.serverPort}/gate")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val body = try {
            conn.inputStream.bufferedReader().readText().trim()
        } finally {
            conn.disconnect()
        }
        if (body.isEmpty()) {
            throw IllegalStateException("no gate available")
        }
        val gateAddress = Json.parseToJsonElement(body).jsonObject["websocketEndpoint"]
            ?.jsonPrimitive?.content.orEmpty()
        if (gateAddress.isEmpty()) throw IllegalStateException("Portal returned no WebSocket Gate endpoint")
        val uri = URI(gateAddress)
        require(uri.scheme in setOf("ws", "wss") && uri.host != null && uri.rawUserInfo == null && uri.fragment == null) {
            "Invalid WebSocket Gate endpoint: $gateAddress"
        }
        val host = uri.host.removeSurrounding("[", "]")
        val port = if (uri.port >= 0) uri.port else if (uri.scheme == "wss") 443 else 80
        logger.info { "获取到 Gate WebSocket 地址: $uri" }

        // 2. 建立连接并等待 WebSocket 升级成功后才允许业务发包。
        val group = NioEventLoopGroup()
        workerGroup = group
        val clientHandler = ClientHandler()
        val bootstrap = Bootstrap()
        bootstrap.group(group)
            .channel(NioSocketChannel::class.java)
            .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val p = ch.pipeline()
                    if (uri.scheme == "wss") {
                        val ssl = SslContextBuilder.forClient().build().newHandler(ch.alloc(), host, port)
                        val parameters = ssl.engine().sslParameters
                        parameters.endpointIdentificationAlgorithm = "HTTPS"
                        ssl.engine().sslParameters = parameters
                        p.addLast(ssl)
                    }
                    p.addLast(HttpClientCodec())
                    p.addLast(HttpObjectAggregator(WebSocketProtocol.MAX_FRAME_LENGTH))
                    p.addLast(WebSocketClientProtocolHandler(
                        WebSocketClientProtocolConfig.newBuilder()
                            .webSocketUri(uri)
                            .allowExtensions(false)
                            .maxFramePayloadLength(WebSocketProtocol.MAX_FRAME_LENGTH)
                            .handshakeTimeoutMillis(WebSocketProtocol.HANDSHAKE_TIMEOUT_MS)
                            .build()
                    ))
                    p.addLast(WebSocketFrameAggregator(WebSocketProtocol.MAX_FRAME_LENGTH))
                    p.addLast(WebSocketNetMessageCodec())
                    p.addLast(IdleStateHandler(0, GuiConfig.SOCKET_WRITER_IDLE_TIME, 0))
                    p.addLast(clientHandler)
                }
            })
        var connected: Channel? = null
        try {
            val connectedChannel = bootstrap.connect(host, port).sync().channel()
            connected = connectedChannel
            connectedChannel.closeFuture().addListener {
                onDisconnected(connectedChannel)
                group.shutdownGracefully()
            }
            clientHandler.handshakeComplete.get(WebSocketProtocol.HANDSHAKE_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS)
            check(connectedChannel.isActive) { "Gate disconnected during WebSocket handshake" }
            channel = connectedChannel
            logger.info { "WebSocket 握手成功，已连接 Gate: $uri" }
        } catch (error: Exception) {
            connected?.close()
            group.shutdownGracefully()
            if (workerGroup === group) workerGroup = null
            throw error
        }
    }

    /** 发送消息到网关（非阻塞 write） */
    fun send(msg: NetMessage): Boolean {
        val ch = channel
        if (ch != null && ch.isActive && ch.isWritable) {
            ch.writeAndFlush(msg)
            return true
        }
        logger.error { "【发送失败】未连接远程服务器" }
        return false
    }

    /** 断开连接 */
    fun disconnect() {
        val previous = channel
        channel = null
        previous?.writeAndFlush(CloseWebSocketFrame())?.addListener(ChannelFutureListener.CLOSE)
        workerGroup?.shutdownGracefully()
        workerGroup = null
    }

    internal fun onDisconnected(disconnected: Channel) {
        if (channel === disconnected) channel = null
    }
}
