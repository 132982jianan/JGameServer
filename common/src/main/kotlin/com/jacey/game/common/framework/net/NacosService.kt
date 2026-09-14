package com.jacey.game.common.framework.net

import akka.actor.ActorRef
import com.alibaba.nacos.api.naming.listener.NamingEvent
import com.alibaba.nacos.api.naming.pojo.Instance
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.akka.resolveAwait
import com.jacey.game.common.framework.nacos.ConfigLoaderService
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
    private val nodeKind2NodeId2NodeInfoMap = ConcurrentHashMap<NodeKind, Map<Int, NodeInfo>>()

    private val actorCacheKey2ActorRefMap = ConcurrentHashMap<ActorCacheKey, ActorRef>()
    private lateinit var selfInstance: com.alibaba.nacos.api.naming.pojo.Instance

    /**
     * 注册本节点到 Nacos
     *
     * @param kind        节点类型
     * @param requestedId 指定节点 id；null = 自动分配（当前同类型最大 id + 1）
     */
    fun start(
        kind: NodeKind,
        requestedId: NodeId?,
        actorName: String,
        connectPath: String = "",
    ): Boolean {
        netConfig = ConfigLoaderService.load<NetConfig>() ?: return false

        val host = netConfig.privateIp.resolve()
        val id = try {
            requestedId?.int ?: getNextInstanceIdByNodeKind(kind)
        } catch (e: Exception) {
            logger.error(e) { "auto id allocation fail (nacos unreachable?)" }
            return false
        }
        val finalPorts = netConfig.calNodePortByNodeKindAndNodeId(kind, id)

        val resolvedConnectPath = connectPath.ifEmpty {
            if (finalPorts.ws > 0) WebSocketProtocol.endpoint(netConfig.publicIp.resolve(), finalPorts.ws) else ""
        }

        selfNodeInfo = NodeInfo(
            kind = kind,
            nodeId = id,
            arteryHost = host,
            arteryPort = finalPorts.artery,
            systemName = systemName(kind, id),
            actorName = actorName,
            publicWs = finalPorts.ws,
            publicHttp = finalPorts.http,
            connectPath = resolvedConnectPath,
        )

        // 先以不可发现状态注册；Application 在业务 Actor/端口全部就绪后再启用。
        val instance = selfNodeInfo.toNacos(enabled = false)
        try {
            Nacos.naming.registerInstance(kind.name, Nacos.conf.group, instance)
        } catch (e: Exception) {
            logger.error(e) { "nacos registerInstance fail (nacos unreachable?)" }
            return false
        }
        selfInstance = instance
        logger.info { "registered to nacos: $kind#$id $host:${finalPorts.artery}" }

        Exit.addExitListener {
            runCatching { Nacos.naming.deregisterInstance(kind.name, Nacos.conf.group, instance) }
        }
        return true
    }

    /** 各 XxxStart 完成后调用，使本节点开始参与发现与负载均衡。 */
    fun markStartupComplete(): Boolean {
        if (!this::selfInstance.isInitialized) return false
        return try {
            selfInstance.isEnabled = true
            // 当前 Nacos 客户端没有 updateInstance API；同 instanceId 重新注册即更新实例。
            Nacos.naming.registerInstance(
                selfNodeInfo.kind.name,
                Nacos.conf.group,
                selfInstance,
            )
            logger.info { "node startup complete: ${selfNodeInfo.kind}#${selfNodeInfo.nodeId}" }
            true
        } catch (error: Exception) {
            logger.error(error) { "mark startup complete failed" }
            false
        }
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

    /** actor system 名：小写 kind + id，如 gate_1 */
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

    // ==================== 发现（所有节点共享完整的节点目录） ====================

    /** 公共启动流程统一订阅全部类型（包括自身类型），新增 NodeKind 自动纳入发现。 */
    fun subscribeAllNodeKinds(): Boolean {
        return try {
            NodeKind.entries.forEach { subscribeByNodeKind(it) }
            true
        } catch (error: Exception) {
            logger.error(error) { "subscribe all node kinds failed" }
            false
        }
    }

    /** 订阅某类型节点的变更，维护路由表。 */
    private fun subscribeByNodeKind(kind: NodeKind) {
        nodeKind2NodeId2NodeInfoMap.putIfAbsent(kind, emptyMap())
        Nacos.naming.subscribe(kind.name, Nacos.conf.group) { event ->
            if (event is NamingEvent) {
                refreshNodeKind(kind, event.instances)
            }
        }
        // 与参考工程 NodeListener.start 一致：订阅后立即填充初始目录。
        refreshNodeKind(kind, Nacos.naming.getAllInstances(kind.name, Nacos.conf.group))
    }

    private fun refreshNodeKind(kind: NodeKind, instances: List<Instance>) {
        val fresh = instances
            .filter { it.isEnabled && it.isHealthy }
            .map { NodeInfo.fromNacos(kind, it) }
            .associateBy { it.nodeId }

        // 与参考工程一致，整体替换目录，避免读到 clear/putAll 之间的空目录。
        nodeKind2NodeId2NodeInfoMap[kind] = fresh
        // 移除下线节点的 actor 缓存。
        actorCacheKey2ActorRefMap.keys.removeAll { key -> key.kind == kind && key.nodeId !in fresh.keys }
        logger.info { "directory[$kind] updated: ${fresh.keys}" }
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
