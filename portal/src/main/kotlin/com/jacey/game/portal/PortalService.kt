package com.jacey.game.portal

import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.WebSocketProtocol
import kotlinx.serialization.Serializable

/** Pure Gate allocation logic. Portal never receives a LoginName. */
object PortalService {
    @Serializable
    data class GateEndpoint(
        val gateId: Int,
        val websocketEndpoint: String,
        val protocolVersion: Int = 1,
    )

    fun selectGate(clientIp: String): GateEndpoint? {
        val gates = NacosService.getNodeInfoListByNodeKind(NodeKind.gate)
            .filter { it.publicWs > 0 }
            .sortedWith(compareBy({ it.nodeId }, { it.arteryHost }))
        if (gates.isEmpty()) return null

        val index = Math.floorMod(clientIp.hashCode(), gates.size)
        val gate = gates[index]
        return GateEndpoint(
            gateId = gate.nodeId,
            websocketEndpoint = gate.connectPath.ifBlank {
                WebSocketProtocol.endpoint(gate.arteryHost, gate.publicWs)
            },
        )
    }
}
