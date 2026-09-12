package com.jacey.game.common.framework.mongo

import kotlinx.serialization.Serializable

/**
 * MongoDB 文档标记接口：所有存 Mongo 的数据类都有 _id
 */
interface DbDocument<Id : Any> {
    val _id: Id
}
