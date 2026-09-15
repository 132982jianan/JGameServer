package com.jacey.game.gate.service

import akka.actor.ActorRef
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.msg.NetMessage

/** Gate 的无状态消息路由；节点发现与负载信息只来自 Nacos。 */
object MessageRouterService {
    fun isHaveOneLobbyForClient(): Boolean {
        return NacosService.getNodeInfoListByNodeKind(NodeKind.lobby).isNotEmpty()
    }

    /** 与 reference code 一致：同一 AccountId 稳定哈希到一个 Lobby。 */
    fun chooseLobbyNodeId(accountId: String): Int? {
        val nodes = NacosService.getNodeInfoListByNodeKind(NodeKind.lobby)
            .sortedBy {
                it.nodeId
            }
        if (nodes.isEmpty()) {
            return null
        }
        return nodes[Math.floorMod(accountId.hashCode(), nodes.size)].nodeId
    }

    suspend fun forwardMsgToLobbyByLobbyNodeId(msg: NetMessage, sender: ActorRef?, lobbyId: Int): Boolean {
        val ref = NacosService.getActorRefByNodeKindAndNodeId(NodeKind.lobby, lobbyId) ?: return false
        ref.tell(msg, sender)
        return true
    }

    /** Match 等跨玩家业务发给当前唯一的 Global；NodeId 排序使选择稳定。 */
    suspend fun forwardToGlobal(msg: NetMessage, sender: ActorRef?): Boolean {
        val nodeId = NacosService.getNodeInfoListByNodeKind(NodeKind.global)
            .minOfOrNull {
                it.nodeId
            } ?: return false

        val globalActorRef = NacosService.getActorRefByNodeKindAndNodeId(NodeKind.global, nodeId) ?: return false
        globalActorRef.tell(msg, sender)

        return true
    }

}
