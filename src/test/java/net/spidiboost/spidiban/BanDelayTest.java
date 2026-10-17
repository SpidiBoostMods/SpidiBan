package net.spidiboost.spidiban;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BanDelayTest {
    @Test void decimalSeconds() {
        assertEquals(5.1, BanDelay.parse("5.10"));
        assertEquals(10.01, BanDelay.parse("10,01"));
        assertEquals(.001, BanDelay.parse("0,001"));
        assertEquals(3600, BanDelay.parse("3600.00"));
        assertEquals("0.0001 сек", BanDelay.describe(.0001));
    }

    @Test void rejectsInvalidValues() {
        for (String value : new String[]{"NaN","Infinity","-1","3600.01","1,2.3","5 extra","", "1e3", "0x1.0p1"})
            assertThrows(IllegalArgumentException.class, () -> BanDelay.parse(value), value);
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -.1, 3601})
            assertThrows(IllegalArgumentException.class, () -> new BanDelay(value));
    }

    @Test void hundredThousandPreciseBoundaries() {
        for (int i = 0; i < 100000; i++) {
            double seconds = (i % 3600001) / 1000D;
            BanDelay delay = new BanDelay(seconds);
            long now = Long.MAX_VALUE - i;
            assertTrue(delay.ready(now));
            delay.complete(now);
            long interval = (long) Math.ceil(seconds * 1_000_000_000D) + 10_000_000L;
            assertFalse(delay.ready(now + interval - 1));
            assertTrue(delay.ready(now + interval));
            delay.reset();
            assertTrue(delay.ready(now));
        }
    }

    @Test void changesApplyFromPreviousConfirmation() {
        BanDelay delay = new BanDelay(5);
        delay.complete(100);
        delay.set(1.1);
        assertFalse(delay.ready(1_110_000_099L));
        assertTrue(delay.ready(1_110_000_100L));
        delay.set(0);
        assertFalse(delay.ready(10_000_099L));
        assertTrue(delay.ready(10_000_100L));
    }
}
