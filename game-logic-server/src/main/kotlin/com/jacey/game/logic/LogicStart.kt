package com.jacey.game.logic

import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.logic.service.ActorFactoryService
import com.jacey.game.logic.service.ActorRefManagerService

/**
 * 逻辑服启动器（object）：组装各 actor 并启动
 */
object LogicStart {
    suspend fun startBusiness(): Boolean {
        val conf = AppConfig.instance

        // 订阅其它节点
        NodeRegister.subscribe(NodeKind.gm)
        NodeRegister.subscribe(NodeKind.battle)
        NodeRegister.subscribe(NodeKind.chat)

        // 创建 actors
        ActorRefManagerService.logicServerActor = ActorFactoryService.createLogicServerActor()
        ActorRefManagerService.loginActor = ActorFactoryService.createLoginActor()

        // 是否是主逻辑服
        if (conf.isMainLogicServer) {
            ActorRefManagerService.registActor = ActorFactoryService.createRegistActor()
            ActorRefManagerService.matchActor = ActorFactoryService.createMatchActor()
        }

        return true
    }
}

