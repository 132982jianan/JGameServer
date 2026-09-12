package com.jacey.game.common.framework.net

import com.jacey.game.common.framework.nacos.Config

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
    /** 对外 IP（客户端连接 gateway 用） */
    val publicIp: AddressStrategy = AddressStrategy(),
    /** 同机多节点端口偏移 */
    val portOffset: OffsetStrategy = OffsetStrategy(),
    /** akka 通用配置（Nacos net.yml 中统一管理，多节点共用） */
    val akka: AkkaSettings = AkkaSettings(),
) : Config {
    @Serializable
    data class AkkaSettings(
        val loglevel: String = "INFO",
        val logDeadLetters: Int = 10,
    ) : Config
    @Serializable
    data class NodePort(
        /** akka artery 端口（节点间通信） */
        val artery: Int = 0,
        /** 客户端 TCP 端口（gateway） */
        val tcp: Int = 0,
        /** 客户端 WebSocket 端口（gateway） */
        val ws: Int = 0,
        /** 对外 HTTP 端口（gm） */
        val http: Int = 0,
    )

    @Serializable
    data class AddressStrategy(
        val address: String? = null,
        val envVar: String? = null,
        val prefix: List<String> = listOf("192.", "172.", "10."),
    ) : Config {
        fun resolve(): String {
            address?.let { return it }
            envVar?.let { v -> System.getenv(v)?.let { return it } }
            for (p in prefix) {
                val ip = localIpByPrefix(p)
                if (ip != null) return ip
            }
            return "127.0.0.1"
        }

        private fun localIpByPrefix(prefix: String): String? {
            return try {
                java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                    .flatMap { it.inetAddresses.asSequence() }
                    .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.hostAddress.startsWith(prefix) }
                    ?.hostAddress
            } catch (e: Exception) {
                null
            }
        }
    }

    @Serializable
    data class OffsetStrategy(
        val value: Int? = null,
        val envVar: String? = null,
    ) : Config {
        fun resolve(): Int {
            value?.let { return it }
            envVar?.let { v -> System.getenv(v)?.let { return it.toIntOrNull() ?: 0 } }
            return 0
        }
    }

    /** 取某节点端口配置；实际端口 = base + offset + nodeId - 1 */
    fun portOf(kind: NodeKind, nodeId: Int): NodePort {
        val base = portRange[kind.name] ?: NodePort()
        val offset = portOffset.resolve()
        fun sock(basePort: Int): Int = if (basePort == 0) 0 else basePort + offset + nodeId - 1
        return NodePort(sock(base.artery), sock(base.tcp), sock(base.ws), sock(base.http))
    }
}
