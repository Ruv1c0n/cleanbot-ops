package com.cleanbot.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Тесты edge-trigger детектора: переход в ERROR, висящий ERROR, дедуп,
 * смена кода, recovery и сброс дедупа после recovery.
 */
class RobotErrorDetectorTest {

    private lateinit var detector: RobotErrorDetector

    /** Дедуп 5 минут, как в проде. */
    private val t0 = 1_000_000L
    private val fiveMinutes = 5L * 60 * 1000

    @Before
    fun setUp() {
        detector = RobotErrorDetector()
    }

    @Test
    fun `переход не-ERROR в ERROR дает Fault`() {
        val alert = detector.onTelemetry("r1", "CLEANING", null, t0)
        assertNull(alert)
        val fault = detector.onTelemetry("r1", "ERROR", "STUCK", t0 + 1000)
        assertEquals(RobotAlert.Fault("r1", "STUCK"), fault)
    }

    @Test
    fun `висящий ERROR не порождает повторных уведомлений`() {
        detector.onTelemetry("r1", "ERROR", "STUCK", t0)
        repeat(10) { i ->
            assertNull(detector.onTelemetry("r1", "ERROR", "STUCK", t0 + 1000 + i))
        }
    }

    @Test
    fun `первый батч сразу с ERROR уведомляет один раз`() {
        assertEquals(
            RobotAlert.Fault("r1", "BRUSH_JAMMED"),
            detector.onTelemetry("r1", "ERROR", "BRUSH_JAMMED", t0)
        )
        assertNull(detector.onTelemetry("r1", "ERROR", "BRUSH_JAMMED", t0 + 1000))
    }

    @Test
    fun `моргание состояния - тот же код в пределах дедупа подавляется`() {
        detector.onTelemetry("r1", "ERROR", "STUCK", t0)
        detector.onTelemetry("r1", "CLEANING", null, t0 + 1000) // recovery
        // Снова та же ошибка раньше, чем через 5 минут, — дедуп.
        assertNull(detector.onTelemetry("r1", "ERROR", "STUCK", t0 + 2000))
    }

    @Test
    fun `тот же код после истечения дедупа уведомляет снова`() {
        detector.onTelemetry("r1", "ERROR", "STUCK", t0)
        detector.onTelemetry("r1", "CLEANING", null, t0 + 1000)
        val fault = detector.onTelemetry("r1", "ERROR", "STUCK", t0 + fiveMinutes + 1)
        assertEquals(RobotAlert.Fault("r1", "STUCK"), fault)
    }

    @Test
    fun `смена кода ошибки - новое уведомление даже в пределах дедупа`() {
        detector.onTelemetry("r1", "ERROR", "STUCK", t0)
        val fault = detector.onTelemetry("r1", "ERROR", "BRUSH_JAMMED", t0 + 5000)
        assertEquals(RobotAlert.Fault("r1", "BRUSH_JAMMED"), fault)
        // И после смены висящий новый код тоже молчит.
        assertNull(detector.onTelemetry("r1", "ERROR", "BRUSH_JAMMED", t0 + 6000))
    }

    @Test
    fun `выход из ERROR дает Recovery`() {
        detector.onTelemetry("r1", "ERROR", "STUCK", t0)
        val alert = detector.onTelemetry("r1", "CHARGING", null, t0 + 1000)
        assertEquals(RobotAlert.Recovered("r1"), alert)
    }

    @Test
    fun `recovery снимает дедуп - следующая авария уведомляет сразу`() {
        detector.onTelemetry("r1", "ERROR", "STUCK", t0)
        detector.onTelemetry("r1", "CLEANING", null, t0 + 1000)
        // Recovery само по себе не subject к дедупу и пришло выше; новая авария — сразу.
        val fault = detector.onTelemetry("r1", "ERROR", "TANK_FULL", t0 + 2000)
        assertEquals(RobotAlert.Fault("r1", "TANK_FULL"), fault)
    }

    @Test
    fun `роботы независимы - ошибка одного не глушит уведомления другого`() {
        assertEquals(RobotAlert.Fault("r1", "STUCK"), detector.onTelemetry("r1", "ERROR", "STUCK", t0))
        assertEquals(
            RobotAlert.Fault("r2", "STUCK"),
            detector.onTelemetry("r2", "ERROR", "STUCK", t0 + 1000)
        )
    }

    @Test
    fun `ошибка без кода обрабатывается как Fault с null`() {
        assertEquals(RobotAlert.Fault("r1", null), detector.onTelemetry("r1", "ERROR", null, t0))
    }
}
