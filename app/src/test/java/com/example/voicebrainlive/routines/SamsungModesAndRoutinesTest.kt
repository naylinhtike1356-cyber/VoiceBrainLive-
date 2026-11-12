package com.example.voicebrainlive.routines

import android.app.Application
import com.example.voicebrainlive.OfflineCommandMatcher
import com.example.voicebrainlive.commands.CompoundCommandExecutor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SamsungModesAndRoutinesTest {

    @Test
    fun testNextTriggerTimeEveryday() {
        val next = RoutineScheduler.nextTriggerTime(hour = 7, minute = 0, repeatDays = emptyList(), fallbackDow = 0)
        assertTrue(next > System.currentTimeMillis() - 1000)
    }

    @Test
    fun testNextTriggerTimeWeekdays() {
        val weekdays = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        val next = RoutineScheduler.nextTriggerTime(hour = 8, minute = 30, repeatDays = weekdays)
        assertTrue(next > System.currentTimeMillis() - 1000)
        
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertTrue(cal.get(Calendar.DAY_OF_WEEK) in weekdays)
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testNextTriggerTimeWeekends() {
        val weekends = listOf(Calendar.SUNDAY, Calendar.SATURDAY)
        val next = RoutineScheduler.nextTriggerTime(hour = 23, minute = 0, repeatDays = weekends)
        assertTrue(next > System.currentTimeMillis() - 1000)

        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertTrue(cal.get(Calendar.DAY_OF_WEEK) in weekends)
        assertEquals(23, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testCompoundCommandParsingRingerAndMusic() {
        val compound = CompoundCommandExecutor.parseSteps("စိုင်းလန့်လုပ်ပြီး သီချင်းဖွင့်ပါ")
        assertNotNull(compound)
        assertEquals(2, compound!!.size)
        assertEquals("set_ringer_silent", compound[0].first)
        assertEquals("media_play", compound[1].first)
    }

    @Test
    fun testCompoundCommandParsingBedtimeWithSleepTimer() {
        val steps = CompoundCommandExecutor.parseSteps("သီချင်းဖွင့်ပြီး ၃၀ မိနစ်ကြာရင် ပိတ်ပေးပါ")
        assertNotNull(steps)
        assertEquals(2, steps!!.size)
        assertEquals("media_play", steps[0].first)
        assertEquals("set_music_sleep_timer", steps[1].first)
        assertEquals("30", steps[1].third)
    }

    @Test
    fun testOfflineCommandMatcherWorkPhrase() {
        val match = OfflineCommandMatcher.matchDirectAction("အလုပ်ဝင်တော့မယ်")
        assertNotNull(match)
        assertEquals("work_mode", match!!.first)
    }

    @Test
    fun testOfflineCommandMatcherBedtimePhrase() {
        val match = OfflineCommandMatcher.matchDirectAction("အိပ်တော့မယ်")
        assertNotNull(match)
        assertEquals("bedtime_mode", match!!.first)
    }

    @Test
    fun testOfflineCommandMatcherRingerModes() {
        val silentMatch = OfflineCommandMatcher.matchDirectAction("စိုင်းလန့်")
        assertNotNull(silentMatch)
        assertEquals("set_ringer_silent", silentMatch!!.first)

        val silentMatch2 = OfflineCommandMatcher.matchDirectAction("အသံတိတ်")
        assertNotNull(silentMatch2)
        assertEquals("set_ringer_silent", silentMatch2!!.first)

        val normalMatch = OfflineCommandMatcher.matchDirectAction("အသံဖွင့်")
        assertNotNull(normalMatch)
        assertEquals("set_ringer_normal", normalMatch!!.first)

        val vibrateMatch = OfflineCommandMatcher.matchDirectAction("ဗိုက်ဘရိတ်")
        assertNotNull(vibrateMatch)
        assertEquals("set_ringer_vibrate", vibrateMatch!!.first)
    }

    @Test
    fun testRoutineStorageSerializationWithSamsungFields() {
        val app = RuntimeEnvironment.getApplication()
        
        val bedtimeRoutine = RoutineStorage.RoutineItem(
            id = 99,
            name = "အိပ်စက်ချိန် မုဒ် (Bedtime)",
            type = "bedtime",
            hour = 22,
            minute = 45,
            musicQuery = "အိပ်ရာဝင်သီချင်း",
            triggerPhrase = "အိပ်တော့မယ်",
            ringerMode = "silent",
            volumePercent = 20,
            sleepTimerMinutes = 45,
            repeatDays = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
            actionsSequence = "silent;vol_20;music;sleep_45"
        )

        RoutineStorage.saveRoutine(app, bedtimeRoutine)

        val loaded = RoutineStorage.getById(app, 99)
        assertNotNull(loaded)
        assertEquals("အိပ်စက်ချိန် မုဒ် (Bedtime)", loaded!!.name)
        assertEquals("bedtime", loaded.type)
        assertEquals(22, loaded.hour)
        assertEquals(45, loaded.minute)
        assertEquals("အိပ်ရာဝင်သီချင်း", loaded.musicQuery)
        assertEquals("အိပ်တော့မယ်", loaded.triggerPhrase)
        assertEquals("silent", loaded.ringerMode)
        assertEquals(20, loaded.volumePercent)
        assertEquals(45, loaded.sleepTimerMinutes)
        assertEquals(listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY), loaded.repeatDays)
        assertEquals("silent;vol_20;music;sleep_45", loaded.actionsSequence)

        // Clean up
        RoutineStorage.delete(app, 99)
        assertNull(RoutineStorage.getById(app, 99))
    }
}
