package dev.rosewood.rosechat.command.argument;

import java.util.Locale;

public record MuteDuration(int amount, Unit unit) {

    public MuteDuration {
        if (amount < 1)
            throw new IllegalArgumentException("mute duration must be positive");
        if (unit == null)
            throw new IllegalArgumentException("mute duration unit must be present");
    }

    public int seconds() {
        long seconds = Math.multiplyExact((long) this.amount, this.unit.seconds());
        if (seconds > Integer.MAX_VALUE)
            return Integer.MAX_VALUE;
        return (int) seconds;
    }

    public boolean indefinite() {
        return this.unit == Unit.YEARS && this.amount > 1000;
    }

    public String displayUnit() {
        return this.amount == 1 ? this.unit.singular() : this.unit.plural();
    }

    public static Unit unit(String canonicalName) {
        return Unit.valueOf(canonicalName.trim().toUpperCase(Locale.ROOT));
    }

    public enum Unit {
        SECOND("second", "seconds", 1L),
        MINUTE("minute", "minutes", 60L),
        HOUR("hour", "hours", 3_600L),
        DAY("day", "days", 86_400L),
        MONTH("month", "months", 2_629_800L),
        YEAR("year", "years", 364L * 86_400L);

        private final String singular;
        private final String plural;
        private final long seconds;

        Unit(String singular, String plural, long seconds) {
            this.singular = singular;
            this.plural = plural;
            this.seconds = seconds;
        }

        String singular() {
            return this.singular;
        }

        String plural() {
            return this.plural;
        }

        long seconds() {
            return this.seconds;
        }
    }
}
