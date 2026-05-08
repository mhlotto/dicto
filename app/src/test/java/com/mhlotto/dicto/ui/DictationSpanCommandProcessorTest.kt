package com.mhlotto.dicto.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationSpanCommandProcessorTest {
    private val processor = DictationSpanCommandProcessor()

    @Test
    fun digitByDigitNumber() {
        assertEquals("155", processor.process("zeta number one five five zeta stop"))
    }

    @Test
    fun digitByDigitWithOh() {
        assertEquals("104", processor.process("zeta number one oh four zeta stop"))
    }

    @Test
    fun composedNumber() {
        assertEquals("21", processor.process("zeta number twenty one zeta stop"))
    }

    @Test
    fun hundredNumber() {
        assertEquals("123", processor.process("zeta number one hundred twenty three zeta stop"))
    }

    @Test
    fun preservesSurroundingText() {
        assertEquals("room 123", processor.process("room zeta number one two three zeta stop"))
    }

    @Test
    fun invalidPayloadRemainsUnchanged() {
        assertEquals(
            "zeta number blah blah zeta stop",
            processor.process("zeta number blah blah zeta stop"),
        )
    }

    @Test
    fun missingEndMarkerRemainsUnchanged() {
        assertEquals(
            "zeta number one five five",
            processor.process("zeta number one five five"),
        )
    }

    @Test
    fun customTriggerWorks() {
        assertEquals(
            "code is 911",
            processor.process("code is alpha bravo number nine one one alpha bravo stop", "alpha bravo"),
        )
    }
}
