package com.example.voicebrainlive

import com.example.voicebrainlive.routines.RoutineScheduler
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for RoutineScheduler timing logic.
 */
class SchedulerTest {

    @Test
    fun testNextTriggerTimeDaily() {
        val now = System.currentTimeMillis()
        // Schedule for 1 hour from now
        val cal = Calendar.getInstance()
        cal.add(Calendar.HOUR_OF_DAY, 1)
        
        val trigger = RoutineScheduler.nextTriggerTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), 0)
        
        // Trigger should be today since it's 1 hour into future
        assertTrue(trigger > now)
        assertTrue(trigger < now + 2L * 3600_000L)
    }

    @Test
    fun testNextTriggerTimePastLoopsToNextDay() {
        val now = System.currentTimeMillis()
        // Schedule for 1 hour ago (should loop to tomorow)
        val cal = Calendar.getInstance()
        cal.add(Calendar.HOUR_OF_DAY, -1)
        
        val trigger = RoutineScheduler.nextTriggerTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), 0)
        
        // Trigger should be tomorrow
        assertTrue(trigger > now + 20L * 3600_000L)
    }

    @Test
    fun testNextTriggerTimeSpecificDay() {
        val cal = Calendar.getInstance()
        val monday = Calendar.MONDAY
        
        // Try to schedule for Monday at 8 AM
        val trigger = RoutineScheduler.nextTriggerTime(8, 0, monday)
        
        val resultCal = Calendar.getInstance()
        resultCal.timeInMillis = trigger
        
        assertEquals(8, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, resultCal.get(Calendar.MINUTE))
        assertEquals(monday, resultCal.get(Calendar.DAY_OF_WEEK))
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected.toLong(), actual.toLong())
    }
}
