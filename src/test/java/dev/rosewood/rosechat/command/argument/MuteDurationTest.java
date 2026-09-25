package dev.rosewood.rosechat.command.argument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MuteDurationTest {

    @Test
    void convertsParsedUnitsWithoutLookingAtTrailingCommandText() {
        assertEquals(90, new MuteDuration(90, MuteDuration.Unit.SECOND).seconds());
        assertEquals(120, new MuteDuration(2, MuteDuration.Unit.MINUTE).seconds());
        assertEquals(7_200, new MuteDuration(2, MuteDuration.Unit.HOUR).seconds());
        assertEquals(172_800, new MuteDuration(2, MuteDuration.Unit.DAY).seconds());
        assertEquals(5_259_600, new MuteDuration(2, MuteDuration.Unit.MONTH).seconds());
    }

    @Test
    void preservesDisplayScaleAndIndefiniteYearBehavior() {
        assertEquals("minute", new MuteDuration(1, MuteDuration.Unit.MINUTE).displayUnit());
        assertEquals("minutes", new MuteDuration(2, MuteDuration.Unit.MINUTE).displayUnit());
        assertFalse(new MuteDuration(1000, MuteDuration.Unit.YEAR).indefinite());
        assertTrue(new MuteDuration(1001, MuteDuration.Unit.YEAR).indefinite());
    }

    @Test
    void clampsVeryLargeFiniteDurationsToRosePlayerStorageRange() {
        assertEquals(Integer.MAX_VALUE, new MuteDuration(Integer.MAX_VALUE, MuteDuration.Unit.YEAR).seconds());
    }
}
