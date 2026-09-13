package com.jacey.game.common.framework.net

import akka.actor.ActorRef
import com.alibaba.nacos.api.naming.listener.NamingEvent
import com.jacey.game.common.framework.akka.AkkaService
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
object NacosService {
    private val logger = KotlinLogging.logger {}

    lateinit var selfNodeInfo: NodeInfo
        private set

    val selfNodeId: Int get() = selfNodeInfo.nodeId

    lateinit var netConfig: NetConfig
        private set

    /** 各类型节点的路由表：kind -> (nodeId -> NodeInfo) */
    private val nodeKind2NodeId2NodeInfoMap = ConcurrentHashMap<NodeKind, ConcurrentHashMap<Int, NodeInfo>>()

    private val actorCacheKey2ActorRefMap = ConcurrentHashMap<ActorCacheKey, ActorRef>()

    /**
     * 注册本节点到 Nacos
     *
     * @param kind        节点类型
     * @param requestedId 指定节点 id；null = 自动分配（当前同类型最大 id + 1）
     */
    fun start(
        kind: NodeKind,
        requestedId: Int?,
        actorName: String,
        connectPath: String = "",
        isMainLogicServer: Boolean = false,
    ): Boolean {
        netConfig = ConfigLoader.load<NetConfig>() ?: return false

        val ports = netConfig.calNodePortByNodeKindAndNodeId(kind, requestedId ?: 1)
        val host = netConfig.privateIp.resolve()
        val id = try {
            requestedId ?: getNextInstanceIdByNodeKind(kind)
        } catch (e: Exception) {
            logger.error(e) { "auto id allocation fail (nacos unreachable?)" }
            return false
        }
        val finalPorts = netConfig.calNodePortByNodeKindAndNodeId(kind, id)

        selfNodeInfo = NodeInfo(
            kind = kind,
            nodeId = id,
            arteryHost = host,
            arteryPort = finalPorts.artery,
            systemName = systemName(kind, id),
            actorName = actorName,
            publicTcp = finalPorts.tcp,
            publicWs = finalPorts.ws,
            publicHttp = finalPorts.http,
            connectPath = connectPath,
            isMainLogicServer = isMainLogicServer,
        )

        val instance = selfNodeInfo.toNacos()
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
        AkkaService.start(
            selfNodeInfo.kind.name,
            selfNodeInfo.nodeId,
            selfNodeInfo.arteryPort,
            selfNodeInfo.arteryHost,
            netConfig.akka.loglevel
        )
    }

    /** actor system 名：小写 kind + id，如 gateway_1 */
    fun systemName(kind: NodeKind, id: Int): String {
        return "${kind.name}_$id"
    }

    /** 自动分配节点 id：同类型当前最大 instanceId + 1 */
    private fun getNextInstanceIdByNodeKind(kind: NodeKind): Int {
        val instances = Nacos.naming.getAllInstances(kind.name, Nacos.conf.group)
        val maxId = instances.maxOfOrNull { it.metadata[NodeInfo.KEY_NODE_ID]?.toIntOrNull() ?: 0 } ?: 0

        // 递增一个
        return maxId + 1
    }

    // ==================== 发现（供业务节点查其它类型节点） ====================

    /** 订阅某类型节点的变更（各业务节点启动时调用，维护路由表） */
    fun subscribeByNodeKind(kind: NodeKind) {
        nodeKind2NodeId2NodeInfoMap.putIfAbsent(kind, ConcurrentHashMap())
        Nacos.naming.subscribe(kind.name, Nacos.conf.group) { event ->
            if (event is NamingEvent) {
                val map = nodeKind2NodeId2NodeInfoMap[kind] ?: return@subscribe
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
                actorCacheKey2ActorRefMap.keys.removeAll { key -> key.kind == kind && key.nodeId !in fresh.keys }
                map.clear()
                map.putAll(fresh)
                logger.info { "directory[$kind] updated: ${map.keys}" }
            }
        }
    }

    /** 拉取某类型全部在线节点（若尚未订阅则先同步拉一次） */
    fun getNodeInfoListByNodeKind(kind: NodeKind): List<NodeInfo> {
        val map = nodeKind2NodeId2NodeInfoMap[kind]
        if (map != null) {
            return map.values.toList()
        }

        val list = Nacos.naming.selectInstances(kind.name, Nacos.conf.group, true)
            .map {
                NodeInfo.fromNacos(kind, it)
            }
        return list
    }

    /** 按 id 取某节点信息 */
    fun getOneNodeInfoByNodeKindAndNodeId(kind: NodeKind, nodeId: Int): NodeInfo? {
        return getNodeInfoListByNodeKind(kind).firstOrNull { it.nodeId == nodeId }
    }

    /**
     * 取某节点的 ActorRef（挂起解析并缓存；同步写法非阻塞）
     * 远端节点 actor path 首次访问时 resolve，之后直接复用
     */
    suspend fun getActorRefByNodeKindAndNodeId(kind: NodeKind, nodeId: Int): ActorRef? {
        val key = ActorCacheKey(kind, nodeId)
        actorCacheKey2ActorRefMap[key]?.let { return it }
        val info = getOneNodeInfoByNodeKindAndNodeId(kind, nodeId) ?: return null
        val selection = AkkaService.system.actorSelection(info.actorPath)

        // 这一步是扩展方法同步非阻塞写法!!!
        val ref = runCatching { selection.resolveAwait() }.getOrNull() ?: return null
        actorCacheKey2ActorRefMap[key] = ref
        return ref
    }

    /** 负载均衡：随机取一个在线节点 actor（原 LoadBalanceService.getOneXxxServer 语义） */
    suspend fun getRandomActorRefByNodeKind(kind: NodeKind): ActorRef? {
        val list = getNodeInfoListByNodeKind(kind)
        if (list.isEmpty()) {
            return null
        }
        val info = list.random()
        return getActorRefByNodeKindAndNodeId(kind, info.nodeId)
    }
}
