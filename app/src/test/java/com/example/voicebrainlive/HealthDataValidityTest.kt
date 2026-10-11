package com.example.voicebrainlive

import com.example.voicebrainlive.health.HealthManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthDataValidityTest {
    @Test
    fun defaultHealthData_isNotRealData() {
        assertFalse(HealthManager.HealthData().hasAnyData)
    }

    @Test
    fun cachedNumbersWithoutProvenance_areNotRealData() {
        val data = HealthManager.HealthData(
            sleepHours = 7.5,
            steps = 6850,
            activeCalories = 340,
            avgHeartRate = 74,
            lastSyncTime = System.currentTimeMillis(),
            dataSource = null
        )
        assertFalse(data.hasAnyData)
    }

    @Test
    fun sourcedHealthData_isValid() {
        val data = HealthManager.HealthData(
            steps = 1234,
            lastSyncTime = System.currentTimeMillis(),
            dataSource = "Health Connect / Samsung Health"
        )
        assertTrue(data.hasAnyData)
    }
}
