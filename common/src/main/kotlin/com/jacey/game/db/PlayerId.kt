package com.jacey.game.db

@JvmInline
value class PlayerId(val value: Int) {
    override fun toString(): String = value.toString()
    fun isZero(): Boolean = value <= 0
}
