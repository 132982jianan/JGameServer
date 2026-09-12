package com.jacey.game.gui

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 服务器连接管理（object 单例，原 ServerNodeManager + NettySocketServer + OnlineClientManager）
 *
 * 启动流程：
 * 1. HTTP GET http://gmHost:gmPort/gateway 获取网关地址 ip:port
 * 2. Netty TCP 连接网关（复用与 gateway 相同的线协议编解码）
 */
object ServerConnection {
    private val logger = KotlinLogging.logger {}

    var channel: io.netty.channel.Channel? = null
        internal set
    val isConnected: Boolean get() = channel?.isActive == true

    private var workerGroup: io.netty.channel.nio.NioEventLoopGroup? = null

    /**
     * 连接网关（挂起）：GM HTTP 取地址 → Netty connect
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        // 1. 从 GM HTTP 获取网关地址
        val url = URL("http://${GuiConfig.serverHost}:${GuiConfig.serverPort}/gateway")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val gatewayAddress = try {
            conn.inputStream.bufferedReader().readText().trim()
        } finally {
            conn.disconnect()
        }
        if (gatewayAddress.isEmpty()) {
            logger.error { "【服务器连接获取异常】无可用网关，GM 返回空" }
            throw IllegalStateException("no gateway available")
        }
        val parts = gatewayAddress.split(":")
        val host = parts[0]
        val port = parts[1].toInt()
        logger.info { "获取到网关地址: $host:$port" }

        // 2. Netty 客户端连接
        val group = io.netty.channel.nio.NioEventLoopGroup()
        workerGroup = group
        val bootstrap = io.netty.bootstrap.Bootstrap()
        bootstrap.group(group)
            .channel(io.netty.channel.socket.nio.NioSocketChannel::class.java)
            .remoteAddress(java.net.InetSocketAddress(host, port))
            .handler(object : io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                override fun initChannel(ch: io.netty.channel.socket.SocketChannel) {
                    val p = ch.pipeline()
                    p.addLast(GuiNetty.ProtocolDecoder())
                    p.addLast(GuiNetty.ProtocolEncoder())
                    p.addLast(io.netty.handler.timeout.IdleStateHandler(0, GuiConfig.SOCKET_WRITER_IDLE_TIME, 0))
                    p.addLast(GuiNetty.ClientHandler())
                }
            })
        val future = bootstrap.connect().sync()
        channel = future.channel()
        logger.info { "已连接网关: $host:$port" }
    }

    /** 发送消息到网关（非阻塞 write） */
    fun send(msg: com.jacey.game.common.msg.NetMessage): Boolean {
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
        channel?.close()
        workerGroup?.shutdownGracefully()
        channel = null
    }
}
