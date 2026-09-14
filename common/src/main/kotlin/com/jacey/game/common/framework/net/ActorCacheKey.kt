package com.jacey.game.common.framework.net

/** 各类型节点的主 actor：(kind, nodeId) -> resolved ActorRef（惰性解析 + 缓存）
 *  key 必须含 kind：所有节点类型 nodeId 都从 1 开始，仅用 nodeId 会串节点（例如 Gate 误取 Insight 的 ref）。 */
 data class ActorCacheKey(val kind: NodeKind, val nodeId: Int)
