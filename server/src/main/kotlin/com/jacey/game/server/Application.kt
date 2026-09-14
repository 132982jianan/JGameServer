package com.jacey.game.server

import com.jacey.game.battle.BattleStart
import com.jacey.game.common.framework.net.NodeId
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.process.Exit
import com.jacey.game.gate.GateStart
import com.jacey.game.global.GlobalStart
import com.jacey.game.insight.InsightStart
import com.jacey.game.lobby.LobbyStart
import com.jacey.game.portal.PortalStart
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required

/**
 * 服务器统一入口（单 jar）
 *
 * 命令行：
 *   java -jar server-all.jar --kind portal
 *   java -jar server-all.jar --kind gate --id 1
 *   java -jar server-all.jar --kind lobby --id 1
 *   java -jar server-all.jar --kind global --id 1
 */
suspend fun main(args: Array<String>) {
    // 与 reference code 一致：入口只负责参数解析、节点分派和进程生命周期。
    val parser = ArgParser("jgame-server", prefixStyle = ArgParser.OptionPrefixStyle.GNU)

    // 节点类型
    val nodeKind by parser.option(
        ArgType.Choice<NodeKind>(),
        fullName = "kind",
        description = "节点类型: ${NodeKind.entries.joinToString(" / ")}"
    ).required()

    //节点id(可不传递)
    val id by parser.option(
        ArgType.Int,
        fullName = "id",
        description = "节点ID（可选，不传自动分配）"
    )

    //解析下
    parser.parse(args)

    val nodeId = id?.let(::NodeId)
    val success = when (nodeKind) {
        NodeKind.portal -> PortalStart.start(nodeId)
        NodeKind.gate -> GateStart.start(nodeId)
        NodeKind.lobby -> LobbyStart.start(nodeId)
        NodeKind.global -> GlobalStart.start(nodeId)
        NodeKind.battle -> BattleStart.start(nodeId)
        NodeKind.insight -> InsightStart.start(nodeId)
    }

    if (success && NacosService.markStartupComplete()) {
        println("[$nodeKind] server started, waiting for exit signal...")
        // 等待退出
        Exit.await()
    } else {
        println("[$nodeKind] server start FAILED")
        Exit.exit(999)
    }
}
