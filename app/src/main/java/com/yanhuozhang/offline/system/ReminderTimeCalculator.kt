package com.yanhuozhang.offline.system

import java.time.Duration
import java.time.ZonedDateTime

object ReminderTimeCalculator {
    fun delayUntilNext(now: ZonedDateTime, hour: Int, minute: Int): Duration {
        require(hour in 0..23) { "小时必须在 0 到 23 之间" }
        require(minute in 0..59) { "分钟必须在 0 到 59 之间" }

        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return Duration.between(now, target)
    }
}
