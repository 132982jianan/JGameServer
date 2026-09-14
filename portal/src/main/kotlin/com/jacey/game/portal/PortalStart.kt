package com.jacey.game.portal

import com.jacey.game.common.CommonStart
import com.jacey.game.common.framework.ktor.Http
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeId
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.akka.NoopNodeActor
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get

object PortalStart {
    suspend fun start(nodeId: NodeId?): Boolean {
        if (!CommonStart.start(NodeKind.portal, nodeId)) return false
        AkkaService.create<NoopNodeActor>(NodeKind.portal.actorName)
        NacosService.subscribeByNodeKind(NodeKind.gate)
        val ports = NacosService.netConfig.calNodePortByNodeKindAndNodeId(
            NodeKind.portal,
            NacosService.selfNodeId,
        )
        if (ports.http <= 0) return false

        Http.start(ports.http) {
            get("/gate") {
                val forwarded = call.request.headers["X-Forwarded-For"]
                    ?.substringBefore(',')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val endpoint = PortalService.selectGate(forwarded ?: call.request.origin.remoteHost)
                if (endpoint == null) {
                    call.respondText(
                        "{\"code\":\"NO_GATE_AVAILABLE\"}",
                        ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable,
                    )
                } else {
                    call.respondText(
                        Http.json.encodeToString(PortalService.GateEndpoint.serializer(), endpoint),
                        ContentType.Application.Json,
                    )
                }
            }
        }
        return true
    }
}
