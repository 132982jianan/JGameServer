package com.jacey.game.server

import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.process.Exit
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import kotlinx.coroutines.runBlocking

/**
 * 服务器统一入口（单 jar）
 *
 * 命令行：
 *   java -jar server-all.jar --kind gm
 *   java -jar server-all.jar --kind gateway --id 1
 *   java -jar server-all.jar --kind logic --id 2
 */
suspend fun main(args: Array<String>) {
    // 尽早设置日志目录（log4j2.xml 的 ${sys:logDir}），必须在任何 logger 使用前
    val kindIdx = args.indexOf("--kind")
    if (kindIdx >= 0 && kindIdx + 1 < args.size) {
        System.setProperty("logDir", args[kindIdx + 1])
    }

    val parser = ArgParser("jgame-server")
    val kind by parser.option(
        ArgType.Choice<NodeKind>(),
        fullName = "kind",
        description = "节点类型: gm / gateway / logic / battle / chat"
    ).required()
    val id by parser.option(
        ArgType.Int,
        fullName = "id",
        description = "节点ID（可选，不传自动分配）"
    )

    parser.parse(args)

    val started = CommonStart.start(kind, id)

    if (started) {
        println("[$kind] server started, waiting for exit signal...")
        Exit.await()
    } else {
        println("[$kind] server start FAILED")
        Exit.exit(999)
    }
}
