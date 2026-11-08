package com.cleanbot.simulator

import com.cleanbot.simulator.repository.FleetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Фильтрация getSessions(id, from, to).
 * Реализация (FleetRepository.getSessions): сравнение по startedAt,
 * границы ВКЛЮЧИТЕЛЬНЫЕ с обеих сторон: from <= startedAt <= to.
 */
class FleetRepositorySessionsTest {

    private fun repo() = FleetRepository(CoroutineScope(SupervisorJob()))

    @Test
    fun `no filters return full history sorted descending`() {
        val all = repo().getSessions("cleanbot-01", null, null)!!
        assertTrue(all.size >= 2, "ожидалась сгенерированная история, получено ${all.size}")
        assertEquals(all, all.sortedByDescending { it.startedAt })
    }

    @Test
    fun `from bound is inclusive`() {
        val repo = repo()
        val all = repo.getSessions("cleanbot-01", null, null)!!
        val middle = all[all.size / 2]
        val from = Instant.parse(middle.startedAt)

        val filtered = repo.getSessions("cleanbot-01", from, null)!!

        assertTrue(filtered.any { it.id == middle.id }, "сессия с startedAt == from должна попадать в выборку")
        assertTrue(filtered.none { it.startedAt < middle.startedAt }, "сессии раньше from не должно быть")
        assertEquals(all.indexOf(middle) + 1, filtered.size, "все сессии от середины до новейшей включительно")
    }

    @Test
    fun `to bound is inclusive`() {
        val repo = repo()
        val all = repo.getSessions("cleanbot-01", null, null)!!
        val middle = all[all.size / 2]
        val to = Instant.parse(middle.startedAt)

        val filtered = repo.getSessions("cleanbot-01", null, to)!!

        assertTrue(filtered.any { it.id == middle.id }, "сессия с startedAt == to должна попадать в выборку")
        assertTrue(filtered.none { it.startedAt > middle.startedAt }, "сессии позже to не должно быть")
        assertEquals(all.size - all.indexOf(middle), filtered.size, "все сессии от старейшей до середины включительно")
    }

    @Test
    fun `narrow window from equals to`() {
        val repo = repo()
        val all = repo.getSessions("cleanbot-01", null, null)!!
        val middle = all[all.size / 2]
        val at = Instant.parse(middle.startedAt)

        val window = repo.getSessions("cleanbot-01", at, at)!!

        assertTrue(window.all { it.startedAt == middle.startedAt })
        assertEquals(1, window.size, "окно from==to должно ловить ровно одну сессию")
    }

    @Test
    fun `future window is empty`() {
        val repo = repo()
        val future = Instant.now().plusSeconds(3600)
        val filtered = repo.getSessions("cleanbot-01", future, null)!!
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `unknown robot returns null`() {
        assertEquals(null, repo().getSessions("no-such-robot", null, null))
    }

    @Test
    fun `malformed from is rejected by Instant_parse`() {
        val repo = repo()
        // URL-слой парсит query-параметры через Instant.parse до вызова getSessions:
        assertFailsWith<java.time.format.DateTimeParseException> {
            Instant.parse("not-a-date")
        }
    }
}
