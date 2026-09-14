package com.jacey.game.portal

import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeKind
import kotlinx.serialization.Serializable

/** Pure Gate allocation logic. Portal never receives a LoginName. */
object PortalService {
    @Serializable
    data class GateEndpoint(
        val gateId: Int,
        val tcpEndpoint: String,
        val websocketEndpoint: String,
        val protocolVersion: Int = 1,
    )

    fun selectGate(clientIp: String): GateEndpoint? {
        val gates = NacosService.getNodeInfoListByNodeKind(NodeKind.gate)
            .filter { it.publicTcp > 0 || it.publicWs > 0 }
            .sortedWith(compareBy({ it.nodeId }, { it.arteryHost }))
        if (gates.isEmpty()) return null

        val index = Math.floorMod(clientIp.hashCode(), gates.size)
        val gate = gates[index]
        val publicHost = gate.connectPath.substringBefore(':').ifBlank { gate.arteryHost }
        return GateEndpoint(
            gateId = gate.nodeId,
            tcpEndpoint = if (gate.publicTcp > 0) "$publicHost:${gate.publicTcp}" else "",
            websocketEndpoint = if (gate.publicWs > 0) "ws://$publicHost:${gate.publicWs}/websocket" else "",
        )
    }
}
