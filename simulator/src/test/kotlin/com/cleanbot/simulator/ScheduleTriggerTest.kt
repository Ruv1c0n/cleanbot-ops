package com.cleanbot.simulator

import com.cleanbot.simulator.model.Schedule
import com.cleanbot.simulator.robot.dueScheduleEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Чистая логика срабатывания расписания (автозапуск уборки). */
class ScheduleTriggerTest {

    private fun entry(day: Int, time: String, mode: String = "dry_sweep") =
        Schedule(id = "$day-$time", robotId = "r1", dayOfWeek = day, time = time, mode = mode)

    @Test
    fun `entry fires on matching day and time`() {
        val entries = listOf(entry(1, "06:00"), entry(3, "22:00", "wet_mopping"))

        assertEquals(listOf(entry(1, "06:00")), dueScheduleEntries(entries, dayOfWeek = 1, time = "06:00"))
        assertEquals(listOf(entry(3, "22:00", "wet_mopping")), dueScheduleEntries(entries, dayOfWeek = 3, time = "22:00"))
    }

    @Test
    fun `entry does not fire on wrong day or minute`() {
        val entries = listOf(entry(1, "06:00"))

        assertTrue(dueScheduleEntries(entries, dayOfWeek = 2, time = "06:00").isEmpty())
        assertTrue(dueScheduleEntries(entries, dayOfWeek = 1, time = "06:01").isEmpty())
    }

    @Test
    fun `several entries in the same minute all fire`() {
        val entries = listOf(entry(5, "12:30"), entry(5, "12:30", "wet_mopping"))

        assertEquals(2, dueScheduleEntries(entries, dayOfWeek = 5, time = "12:30").size)
    }
}
