package com.jacey.game.common.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/** 日期时间转换工具 */
object DateTimeUtil {
    private val defaultZoneId: ZoneId = ZoneId.systemDefault()
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun instantToTimestamp(instant: Instant): Long = instant.toEpochMilli()
    fun timestampToInstant(timestamp: Long): Instant = Instant.ofEpochMilli(timestamp)
    fun timestampToDate(timestamp: Long): Date = Date.from(timestampToInstant(timestamp))
    fun dateToTimestamp(date: Date): Long = date.time

    fun localDateTimeToInstant(localDateTime: LocalDateTime): Instant =
        localDateTime.atZone(defaultZoneId).toInstant()

    fun instantToLocalDateTime(instant: Instant): LocalDateTime =
        LocalDateTime.ofInstant(instant, defaultZoneId)

    fun timestampToLocalDateTime(timestamp: Long): LocalDateTime =
        instantToLocalDateTime(timestampToInstant(timestamp))

    fun localDateTimeToTimestamp(localDateTime: LocalDateTime): Long =
        localDateTime.atZone(defaultZoneId).toInstant().toEpochMilli()

    fun localDateTimeToDateTimeString(localDateTime: LocalDateTime): String =
        localDateTime.format(formatter)

    fun dateTimeStringToLocalDateTime(dateTimeString: String): LocalDateTime =
        LocalDateTime.parse(dateTimeString, formatter)

    fun getCurrentTimestamp(): Long = System.currentTimeMillis()
}
