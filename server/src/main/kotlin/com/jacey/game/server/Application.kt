package com.jacey.game.server

import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.process.Exit
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
    // 尽早设置日志目录（log4j2.xml 的 ${sys:logDir}），必须在任何 logger 使用前
    val kindIdx = args.indexOf("--kind")
    if (kindIdx >= 0 && kindIdx + 1 < args.size) {
        System.setProperty("logDir", args[kindIdx + 1])
    }

    // GNU 风格：同时支持 "--kind lobby" 与 "--kind=lobby"（部署 command 用等号写法）
    val parser = ArgParser("jgame-server", prefixStyle = ArgParser.OptionPrefixStyle.GNU)

    // 节点类型
    val kind by parser.option(
        ArgType.Choice<NodeKind>(),
        fullName = "kind",
        description = "节点类型: portal / gate / lobby / global / battle / insight"
    ).required()

    //节点id(可不传递)
    val id by parser.option(
        ArgType.Int,
        fullName = "id",
        description = "节点ID（可选，不传自动分配）"
    )

    //解析下
    parser.parse(args)

    // 启动
    val successFlag = CommonStart.start(kind, id)
    if (successFlag) {
        println("[$kind] server started, waiting for exit signal...")
        // 等待退出
        Exit.await()
    } else {
        println("[$kind] server start FAILED")
        Exit.exit(999)
    }
}
