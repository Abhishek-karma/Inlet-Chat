package com.assistant.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bucket boundaries of the home greeting. */
class GreetingTest {

    @Test
    fun bucketsCoverTheDay() {
        assertEquals(Greeting.EVENING, timeOfDayGreeting(0))
        assertEquals(Greeting.EVENING, timeOfDayGreeting(4))
        assertEquals(Greeting.MORNING, timeOfDayGreeting(5))
        assertEquals(Greeting.MORNING, timeOfDayGreeting(11))
        assertEquals(Greeting.AFTERNOON, timeOfDayGreeting(12))
        assertEquals(Greeting.AFTERNOON, timeOfDayGreeting(16))
        assertEquals(Greeting.EVENING, timeOfDayGreeting(17))
        assertEquals(Greeting.EVENING, timeOfDayGreeting(23))
    }
}
