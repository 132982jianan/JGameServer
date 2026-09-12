package com.jacey.game.gui.service

import com.jacey.game.gui.jframe.BattleFrame
import com.jacey.game.gui.jframe.HallFrame
import com.jacey.game.gui.jframe.LoginFrame

/**
 * 界面引用（object 单例，原 ViewManager）
 */
object ViewManagerService {
    var loginFrame: LoginFrame? = null
    var hallFrame: HallFrame? = null
    var battleFrame: BattleFrame? = null
}