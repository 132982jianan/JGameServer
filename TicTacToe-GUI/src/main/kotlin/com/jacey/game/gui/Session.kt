package com.jacey.game.gui

import com.jacey.game.common.proto3.CommonMsg

/**
 * 客户端会话状态（object 单例，原 OnlineClientManager）
 */
object Session {
    var userInfo: CommonMsg.UserInfo? = null
    var isLogin: Boolean = false
}
