package com.jacey.game.common.framework.ktor

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * Ktor HTTP 服务器封装（GM 后台等对外 HTTP 用），替代 Spring Boot web。
 */
object Http {
    private val logger = KotlinLogging.logger {}

    /** 统一 JSON 配置（与原 ResultVO 输出兼容） */
    val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * 启动内嵌 Netty HTTP 服务
     *
     * @param port         监听端口
     * @param contextPath  上下文路径（保持与原 GM 部署一致，可传空串）
     * @param routingSetup 路由定义
     */
    fun start(
        port: Int,
        contextPath: String = "",
        routingSetup: io.ktor.server.routing.Routing.() -> Unit,
    ): io.ktor.server.engine.EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { json(json) }
            routing {
                // 健康检查
                get("/health") {
                    call.respondText("ok", ContentType.Text.Plain)
                }
                routingSetup()
            }
        }
        server.start(wait = false)
        logger.info { "ktor http started on port $port (context=$contextPath)" }
        return server
    }
}
