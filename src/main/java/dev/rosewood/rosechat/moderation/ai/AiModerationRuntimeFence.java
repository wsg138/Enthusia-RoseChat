package dev.rosewood.rosechat.moderation.ai;

import java.util.concurrent.atomic.AtomicLong;

final class AiModerationRuntimeFence {
    private final AtomicLong generation = new AtomicLong();

    long current() {
        return generation.get();
    }

    long advance() {
        return generation.incrementAndGet();
    }

    boolean isCurrent(long candidate) {
        return generation.get() == candidate;
    }
}
