package com.jacey.game.gm

import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NacosService

/**
 * GM 网关入口查询（原 GmRegistry 的注册表部分已由 Nacos 取代）
 */
object GmRegistry {

    /** 获取最空闲网关连接地址（Nacos 目录 + connectPath metadata；原 zset 负载排序由 Nacos 健康检查替代） */
    fun getLeisureGatewayConnectPath(): String? {
        return NacosService.getNodeInfoListByNodeKind(NodeKind.gateway)
            .filter { it.connectPath.isNotEmpty() }
            .minByOrNull { it.nodeId }?.connectPath
    }
}
