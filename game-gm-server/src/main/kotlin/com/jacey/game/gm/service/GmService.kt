package com.jacey.game.gm.service

import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeKind

/**
 * GM 网关入口查询（原 GmRegistry 的注册表部分已由 Nacos 取代）
 */
object GmService {

    /** 获取最空闲网关连接地址（Nacos 目录 + connectPath metadata；原 zset 负载排序由 Nacos 健康检查替代） */
    fun getGatewayConnectPathFromNacos(): String? {
        return NacosService.getNodeInfoListByNodeKind(NodeKind.gateway)
            .filter { it.connectPath.isNotEmpty() }
            .minByOrNull { it.nodeId }?.connectPath
    }
}