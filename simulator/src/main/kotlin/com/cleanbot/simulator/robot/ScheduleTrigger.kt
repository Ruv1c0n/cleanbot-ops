package com.cleanbot.simulator.robot

import com.cleanbot.simulator.model.Schedule

/**
 * Чистая логика срабатывания расписания: записи, которые должны запустить
 * уборку в указанный день недели (1..7, понедельник = 1) и время "HH:mm".
 * Вынесена из FleetRepository, чтобы покрываться юнит-тестами без корутин.
 */
fun dueScheduleEntries(entries: List<Schedule>, dayOfWeek: Int, time: String): List<Schedule> =
    entries.filter { it.dayOfWeek == dayOfWeek && it.time == time }
