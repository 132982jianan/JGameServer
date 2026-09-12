package com.jacey.game.common.framework.net

/**
 * 节点完整信息
 *
 * 注册到 Nacos 的实例数据：
 * - ip + arteryPort 作为实例地址（akka artery）
 * - actorPath / 对外端口等放 metadata
 */
data class NodeInfo(
    val kind: NodeKind,
    val nodeId: Int,
    /** akka artery 地址：akka://sys@host:port */
    val arteryHost: String,
    val arteryPort: Int,
    /** actor system 名（拼 path 用） */
    val systemName: String,
    /** 主 actor 名（各节点统一 user/serverActor 风格） */
    val actorName: String,
    /** 对外 TCP 端口（gateway 用，0 表示无） */
    val publicTcp: Int,
    /** 对外 WebSocket 端口（gateway 用，0 表示无） */
    val publicWs: Int,
    /** 对外 HTTP 端口（gm 用，0 表示无） */
    val publicHttp: Int,
) {
    /** 完整 actor path：akka://<sys>@<host>:<port>/user/<actor> */
    val actorPath: String
        get() = "akka://$systemName@$arteryHost:$arteryPort/user/$actorName"

    companion object {
        const val KEY_ACTOR_PATH = "actorPath"
        const val KEY_SYSTEM_NAME = "systemName"
        const val KEY_ACTOR_NAME = "actorName"
        const val KEY_NODE_ID = "nodeId"
        const val KEY_PUBLIC_TCP = "publicTcp"
        const val KEY_PUBLIC_WS = "publicWs"
        const val KEY_PUBLIC_HTTP = "publicHttp"

        /** 从 Nacos Instance 反序列化 */
        fun fromNacos(kind: NodeKind, ins: com.alibaba.nacos.api.naming.pojo.Instance): NodeInfo {
            val meta = ins.metadata
            return NodeInfo(
                kind = kind,
                nodeId = meta[KEY_NODE_ID]?.toIntOrNull() ?: ins.port,
                arteryHost = ins.ip,
                arteryPort = ins.port,
                systemName = meta[KEY_SYSTEM_NAME] ?: "",
                actorName = meta[KEY_ACTOR_NAME] ?: "serverActor",
                publicTcp = meta[KEY_PUBLIC_TCP]?.toIntOrNull() ?: 0,
                publicWs = meta[KEY_PUBLIC_WS]?.toIntOrNull() ?: 0,
                publicHttp = meta[KEY_PUBLIC_HTTP]?.toIntOrNull() ?: 0,
            )
        }
    }

    /** 序列化为 Nacos Instance */
    fun toNacos(): com.alibaba.nacos.api.naming.pojo.Instance {
        val ins = com.alibaba.nacos.api.naming.pojo.Instance()
        ins.serviceName = kind.name
        ins.instanceId = nodeId.toString()
        ins.ip = arteryHost
        ins.port = arteryPort
        ins.isEphemeral = true
        ins.metadata = mutableMapOf(
            KEY_NODE_ID to nodeId.toString(),
            KEY_SYSTEM_NAME to systemName,
            KEY_ACTOR_NAME to actorName,
            KEY_ACTOR_PATH to actorPath,
        )
        if (publicTcp > 0) ins.metadata[KEY_PUBLIC_TCP] = publicTcp.toString()
        if (publicWs > 0) ins.metadata[KEY_PUBLIC_WS] = publicWs.toString()
        if (publicHttp > 0) ins.metadata[KEY_PUBLIC_HTTP] = publicHttp.toString()
        return ins
    }
}
