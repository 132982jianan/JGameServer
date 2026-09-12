package com.jacey.game.common.framework.net

import akka.actor.ActorRef
import com.alibaba.nacos.api.naming.listener.NamingEvent
import com.jacey.game.common.framework.akka.Akka
import com.jacey.game.common.framework.akka.resolveAwait
import com.jacey.game.common.framework.nacos.ConfigLoader
import com.jacey.game.common.framework.nacos.Nacos
import com.jacey.game.common.framework.process.Exit
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * 节点注册与发现（单例）
 *
 * 注册：把本节点（akka artery 地址 + 端口 + metadata）注册到 Nacos naming。
 * 发现：订阅各节点类型变更，维护 nodeId -> ActorRef 路由表（NodeDirectory）。
 *
 * 替代原 Mongo *LoadBalance 注册表。
 */
object NodeRegister {
    private val logger = KotlinLogging.logger {}

    lateinit var selfInfo: NodeInfo
        private set

    val selfId: Int get() = selfInfo.nodeId

    lateinit var netConf: NetConfig
        private set

    /** 各类型节点的路由表：kind -> (nodeId -> NodeInfo) */
    private val directory = ConcurrentHashMap<NodeKind, ConcurrentHashMap<Int, NodeInfo>>()

    /** 各类型节点的主 actor：nodeId -> resolved ActorRef（惰性解析 + 缓存） */
    private val actorRefs = ConcurrentHashMap<Int, akka.actor.ActorRef>()

    /**
     * 注册本节点到 Nacos
     *
     * @param kind        节点类型
     * @param requestedId 指定节点 id；null = 自动分配（当前同类型最大 id + 1）
     */
    fun start(kind: NodeKind, requestedId: Int?, actorName: String): Boolean {
        netConf = ConfigLoader.load<NetConfig>() ?: return false

        val ports = netConf.portOf(kind, requestedId ?: 1)
        val host = netConf.privateIp.resolve()
        val id = try {
            requestedId ?: autoId(kind)
        } catch (e: Exception) {
            logger.error(e) { "auto id allocation fail (nacos unreachable?)" }
            return false
        }
        val finalPorts = netConf.portOf(kind, id)

        selfInfo = NodeInfo(
            kind = kind,
            nodeId = id,
            arteryHost = host,
            arteryPort = finalPorts.artery,
            systemName = systemName(kind, id),
            actorName = actorName,
            publicTcp = finalPorts.tcp,
            publicWs = finalPorts.ws,
            publicHttp = finalPorts.http,
        )

        val instance = selfInfo.toNacos()
        try {
            Nacos.naming.registerInstance(kind.name, Nacos.conf.group, instance)
        } catch (e: Exception) {
            logger.error(e) { "nacos registerInstance fail (nacos unreachable?)" }
            return false
        }
        logger.info { "registered to nacos: $kind#$id $host:${finalPorts.artery}" }

        Exit.addExitListener {
            runCatching { Nacos.naming.deregisterInstance(kind.name, Nacos.conf.group, instance) }
        }
        return true
    }

    /** 启动 actor system（注册成功后调用，端口已确定；loglevel 等来自 Nacos net.yml） */
    fun startActorSystem() {
        Akka.start(
            selfInfo.kind.name, selfInfo.nodeId, selfInfo.arteryPort, selfInfo.arteryHost,
            netConf.akka.loglevel
        )
    }

    /** actor system 名：小写 kind + id，如 gateway_1 */
    fun systemName(kind: NodeKind, id: Int): String {
        return "${kind.name}_$id"
    }

    /** 自动分配节点 id：同类型当前最大 instanceId + 1 */
    private fun autoId(kind: NodeKind): Int {
        val instances = Nacos.naming.getAllInstances(kind.name, Nacos.conf.group)
        val maxId = instances.maxOfOrNull { it.metadata[NodeInfo.KEY_NODE_ID]?.toIntOrNull() ?: 0 } ?: 0
        return maxId + 1
    }

    // ==================== 发现（供业务节点查其它类型节点） ====================

    /** 订阅某类型节点的变更（各业务节点启动时调用，维护路由表） */
    fun subscribe(kind: NodeKind) {
        directory.putIfAbsent(kind, ConcurrentHashMap())
        Nacos.naming.subscribe(kind.name, Nacos.conf.group) { event ->
            if (event is NamingEvent) {
                val map = directory[kind] ?: return@subscribe
                val fresh = event.instances
                    .filter {
                        it.isEnabled && it.isHealthy
                    }
                    .map {
                        NodeInfo.fromNacos(kind, it)
                    }
                    .associateBy {
                        it.nodeId
                    }

                // 移除下线节点的 actor 缓存
                actorRefs.keys.removeAll { nodeId -> nodeId !in fresh.keys && map[nodeId]?.let { it.kind == kind } == true }
                map.clear()
                map.putAll(fresh)
                logger.info { "directory[$kind] updated: ${map.keys}" }
            }
        }
    }

    /** 拉取某类型全部在线节点（若尚未订阅则先同步拉一次） */
    fun nodesOf(kind: NodeKind): List<NodeInfo> {
        val map = directory[kind]
        if (map != null) return map.values.toList()
        val list = Nacos.naming.selectInstances(kind.name, Nacos.conf.group, true)
            .map {
                NodeInfo.fromNacos(kind, it)
            }
        return list
    }

    /** 按 id 取某节点信息 */
    fun nodeOf(kind: NodeKind, nodeId: Int): NodeInfo? {
        return nodesOf(kind).firstOrNull { it.nodeId == nodeId }
    }

    /**
     * 取某节点的 ActorRef（挂起解析并缓存；同步写法非阻塞）
     * 远端节点 actor path 首次访问时 resolve，之后直接复用
     */
    suspend fun actorRefOf(kind: NodeKind, nodeId: Int): ActorRef? {
        actorRefs[nodeId]?.let { return it }
        val info = nodeOf(kind, nodeId) ?: return null
        val selection = Akka.system.actorSelection(info.actorPath)
        val ref = runCatching { selection.resolveAwait() }.getOrNull() ?: return null
        actorRefs[nodeId] = ref
        return ref
    }

    /** 负载均衡：随机取一个在线节点 actor（原 LoadBalanceService.getOneXxxServer 语义） */
    suspend fun randomActorRefOf(kind: NodeKind): ActorRef? {
        val list = nodesOf(kind)
        if (list.isEmpty()) {
            return null
        }
        val info = list.random()
        return actorRefOf(kind, info.nodeId)
    }
}
