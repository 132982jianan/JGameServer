package com.jacey.game.insight

import com.jacey.game.common.CommonStart
import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.ktor.Http
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeId
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.constants.CookieConstant
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.akka.NoopNodeActor
import com.jacey.game.db.service.GmUserService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import java.util.UUID

/**
 * Insight HTTP 路由与启动器（由原 GM 管理服务演进而来）。
 *
 * Insight 为 HTTP 管理节点：
 * - GET /gm/gmUserLogin?gmUserName=&passwordMD5=  → JSON ResultVO，Set-Cookie token
 * - GET /gm/executeGmCmd                          → JSON ResultVO
 */
object InsightStart {
    private val logger = KotlinLogging.logger {}

    /** ResultVO 结构（与原 Spring 版 JSON 输出兼容：{code, msg, data}） */
    @kotlinx.serialization.Serializable
    data class ResultVO(val code: Int, val msg: String, val data: String? = null)

    suspend fun start(nodeId: NodeId?): Boolean {
        if (!CommonStart.start(NodeKind.insight, nodeId)) return false
        AkkaService.create<NoopNodeActor>(NodeKind.insight.actorName)
        ensureAdmin()

        startHttp(AppConfig.instance)

        return true
    }

    /** 原 GmUserSeedConfig：admin 账号种子 */
    private suspend fun ensureAdmin() {
        if (!GmUserService.existsAdmin()) {
            GmUserService.saveAdminGmUserEntity()
            logger.info { "GmUser seed: created default admin account (userId=1)" }
        }
    }

    private fun startHttp(conf: AppConfig) {
        val ports = NacosService.netConfig.calNodePortByNodeKindAndNodeId(NodeKind.insight, NacosService.selfNodeId)
        val httpPort = if (ports.http > 0) {
            ports.http
        } else {
            80
        }

        Http.start(httpPort, "") {
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

        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondJson(vo: ResultVO) {
        respondText(Http.json.encodeToString(ResultVOSerializer, vo), io.ktor.http.ContentType.Application.Json)
    }

    private val ResultVOSerializer = ResultVO.serializer()
}
