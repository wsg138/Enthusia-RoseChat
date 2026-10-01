package dev.rosewood.rosechat.moderation.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiModerationRuntimeFenceTest {
    @Test
    void advancingInvalidatesOlderRuntimeTokens() {
        AiModerationRuntimeFence fence = new AiModerationRuntimeFence();
        long first = fence.current();
        assertTrue(fence.isCurrent(first));
        long second = fence.advance();
        assertFalse(fence.isCurrent(first));
        assertTrue(fence.isCurrent(second));
    }

    @Test
    void everyReloadGenerationInvalidatesThePriorOne() {
        AiModerationRuntimeFence fence = new AiModerationRuntimeFence();
        long initial = fence.current();
        long firstReload = fence.advance();
        long secondReload = fence.advance();
        assertFalse(fence.isCurrent(initial));
        assertFalse(fence.isCurrent(firstReload));
        assertTrue(fence.isCurrent(secondReload));
    }
}
