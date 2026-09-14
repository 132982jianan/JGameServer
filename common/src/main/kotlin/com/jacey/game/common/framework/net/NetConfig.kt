package com.jacey.game.common.framework.net

import com.jacey.game.common.framework.nacos.IConfig

import kotlinx.serialization.Serializable

/**
 * 网络配置（对应 conf/net.yml）
 *
 * 各节点端口段 + IP 解析策略（优先级：address > envVar > prefix 网卡匹配 > localhost）
 */
@Serializable
data class NetConfig(
    /** 各节点类型的端口配置，key = 节点类型名 */
    val portRange: Map<String, NodePort> = emptyMap(),
    /** 私有 IP（akka artery / 节点间通信） */
    val privateIp: AddressStrategy = AddressStrategy(),
    /** 对外 IP（客户端连接 Gate 用） */
    val publicIp: AddressStrategy = AddressStrategy(),
    /** 同机多节点端口偏移 */
    val portOffset: OffsetStrategy = OffsetStrategy(),
    /** akka 通用配置（Nacos net.yml 中统一管理，多节点共用） */
    val akka: AkkaSettings = AkkaSettings(),
) : IConfig {

    /** 取某节点端口配置；实际端口 = base + offset + nodeId - 1 */
    fun calNodePortByNodeKindAndNodeId(kind: NodeKind, nodeId: Int): NodePort {
        val base = portRange[kind.name] ?: NodePort()
        val offset = portOffset.resolve()

        return NodePort(
            calPort(base.artery, offset, nodeId),
            calPort(base.tcp, offset, nodeId),
            calPort(base.ws, offset, nodeId),
            calPort(base.http, offset, nodeId)
        )
    }

    private fun calPort(basePort: Int, offset: Int, nodeId: Int): Int {
        return if (basePort == 0) {
            0
        } else {
            basePort + offset + nodeId - 1
        }
    }
}
