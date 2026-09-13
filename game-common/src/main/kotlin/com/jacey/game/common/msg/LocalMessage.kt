package com.jacey.game.common.msg

/** 内部服务器通讯实体（本节点内 self 调度/定时任务用，不跨节点） */
class LocalMessage(msgId: Int, lite: Any? = null) : AbstractMessage() {
    init {
        this.msgId = msgId
        this.lite = lite
    }
}