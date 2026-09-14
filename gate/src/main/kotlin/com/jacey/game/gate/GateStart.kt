package com.jacey.game.gate

import com.jacey.game.common.CommonStart
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NodeId
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.gate.actor.GateRootActor
import com.jacey.game.gate.netty.NettyServer
import com.jacey.game.common.framework.process.Exit

/** Gate 只持有客户端连接、GateActor 和到业务节点的路由。 */
object GateStart {
    suspend fun start(nodeId: NodeId?): Boolean {
        if (!CommonStart.start(NodeKind.gate, nodeId)) {
            return false
        }

        AkkaService.create<GateRootActor>(NodeKind.gate.actorName)

        NettyServer.start()
        Exit.addExitListener {
            NettyServer.shutdown()
        }
        return true
    }
}
