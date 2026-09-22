package dev.rosewood.rosechat.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterWarningTest {

    @Test
    void warningLocaleKeysRemainStable() {
        assertEquals(Map.of(
                FilterWarning.CAPS, "blocked-caps",
                FilterWarning.SPAM, "blocked-spam",
                FilterWarning.URL, "blocked-url",
                FilterWarning.SWEAR, "blocked-language"
        ), java.util.Arrays.stream(FilterWarning.values())
                .collect(java.util.stream.Collectors.toMap(value -> value, FilterWarning::getWarning)));
    }
}
