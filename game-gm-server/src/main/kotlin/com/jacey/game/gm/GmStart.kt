package com.jacey.game.gm

import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.ktor.Http
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.common.constants.CookieConstant
import com.jacey.game.db.service.GmUserService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import java.util.UUID

/**
 * GM HTTP 路由与启动器（原 GmController/ClientController/FilterConfig/RequestInterceptor）
 *
 * 契约不变：
 * - GET /gm/gmUserLogin?gmUserName=&passwordMD5=  → JSON ResultVO，Set-Cookie token
 * - GET /gm/executeGmCmd                          → JSON ResultVO
 * - GET /gateway                                  → text 网关连接地址
 */
object GmStart {
    private val logger = KotlinLogging.logger {}

    /** ResultVO 结构（与原 Spring 版 JSON 输出兼容：{code, msg, data}） */
    @kotlinx.serialization.Serializable
    data class ResultVO(val code: Int, val msg: String, val data: String? = null)

    suspend fun startBusiness(): Boolean {
        NodeRegister.subscribe(NodeKind.logic)
        NodeRegister.subscribe(NodeKind.battle)
        NodeRegister.subscribe(NodeKind.chat)
        NodeRegister.subscribe(NodeKind.gateway)
        AkkaService.create<GmActor>("gmActor")
        seedAdmin()
        startHttp(AppConfig.instance)
        return true
    }

    /** 原 GmUserSeedConfig：admin 账号种子 */
    private suspend fun seedAdmin() {
        if (!GmUserService.existsAdmin()) {
            GmUserService.saveAdmin()
            logger.info { "GmUser seed: created default admin account (userId=1)" }
        }
    }

    private fun startHttp(conf: AppConfig) {
        val ports = NodeRegister.netConf.portOf(NodeKind.gm, NodeRegister.selfId)
        val httpPort = if (ports.http > 0) ports.http else 80
        Http.start(httpPort, "") {
            // 原 GmController.gmUserLogin
            get("/gm/gmUserLogin") {
                val gmUserName = call.request.queryParameters["gmUserName"]
                val passwordMD5 = call.request.queryParameters["passwordMD5"]
                val tokenCookie = call.request.cookies[CookieConstant.TOKEN]
                // 已有有效 token
                if (tokenCookie != null && !GmUserService.getGmUserTokenCache(tokenCookie).isNullOrEmpty()) {
                    call.respondJson(ResultVO(0, "成功"))
                    return@get
                }
                val user = gmUserName?.let { GmUserService.findGmUserByUsername(it) }
                if (user == null || user.passwordMD5 != passwordMD5?.uppercase()) {
                    call.respondJson(ResultVO(1, "登录失败"))
                    return@get
                }
                // 生成 token 并缓存
                val token = UUID.randomUUID().toString()
                GmUserService.setGmUserTokenCache(token, CookieConstant.EXPIRE)
                call.response.cookies.append(
                    io.ktor.http.Cookie(
                        CookieConstant.TOKEN, token,
                        path = "/", maxAge = CookieConstant.EXPIRE
                    )
                )
                call.respondJson(ResultVO(0, "成功"))
            }
            // 原 GmController.executeGmCmd（原版 TODO，保持）
            get("/gm/executeGmCmd") {
                call.respondJson(ResultVO(0, "成功"))
            }
            // 原 ClientController.getLeisureGateway：下发空闲网关连接地址
            get("/gateway") {
                val connectPath = GmRegistry.getLeisureGatewayConnectPath()
                if (connectPath != null) {
                    call.respondText(connectPath, io.ktor.http.ContentType.Text.Plain)
                } else {
                    call.respondText("", io.ktor.http.ContentType.Text.Plain)
                }
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondJson(vo: ResultVO) {
        respondText(Http.json.encodeToString(ResultVOSerializer, vo), io.ktor.http.ContentType.Application.Json)
    }

    private val ResultVOSerializer = ResultVO.serializer()
}
