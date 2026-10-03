package dev.rosewood.rosechat.moderation.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public final class AiModerationMetrics {
    private static final int MAX_LATENCY_SAMPLES = 2_048;

    private final LongAdder requests = new LongAdder();
    private final LongAdder successes = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder allows = new LongAdder();
    private final LongAdder alerts = new LongAdder();
    private final LongAdder deletes = new LongAdder();
    private final LongAdder lateDeletes = new LongAdder();
    private final LongAdder shadowFlags = new LongAdder();
    private final LongAdder centralTimeouts = new LongAdder();
    private final LongAdder centralUnavailable = new LongAdder();
    private final LongAdder centralConflicts = new LongAdder();
    private final LongAdder centralDegraded = new LongAdder();
    private final LongAdder centralBlocked = new LongAdder();
    private final Deque<Long> latencySamples = new ArrayDeque<>();

    public void requestStarted() {
        requests.increment();
    }

    public void requestSucceeded(long latencyMs) {
        successes.increment();
        recordLatency(latencyMs);
    }

    public void requestFailed(long latencyMs) {
        failures.increment();
        recordLatency(latencyMs);
    }

    public void requestRateLimited() {
        rateLimited.increment();
    }

    public void allowed() {
        allows.increment();
    }

    public void alerted() {
        alerts.increment();
    }

    public void deleted(boolean late) {
        deletes.increment();
        if (late) {
            lateDeletes.increment();
        }
    }

    public void shadowFlagged() {
        shadowFlags.increment();
    }

    /** Central request hit the client deadline. */
    public void centralTimeout() {
        centralTimeouts.increment();
    }

    /** Central service answered 503 (queue/deadline saturation). */
    public void centralUnavailable() {
        centralUnavailable.increment();
    }

    /** Central service answered 409 idempotency conflict. */
    public void centralConflict() {
        centralConflicts.increment();
    }

    /** Central response was degraded/fail-open and chat was allowed. */
    public void centralDegraded() {
        centralDegraded.increment();
    }

    /** Central service returned an enforced BLOCK decision. */
    public void centralBlocked() {
        centralBlocked.increment();
    }

    private synchronized void recordLatency(long latencyMs) {
        latencySamples.addLast(Math.max(0L, latencyMs));
        while (latencySamples.size() > MAX_LATENCY_SAMPLES) {
            latencySamples.removeFirst();
        }
    }

    public synchronized Snapshot snapshot() {
        List<Long> sorted = new ArrayList<>(latencySamples);
        Collections.sort(sorted);
        return new Snapshot(
                requests.sum(),
                successes.sum(),
                failures.sum(),
                rateLimited.sum(),
                allows.sum(),
                alerts.sum(),
                deletes.sum(),
                lateDeletes.sum(),
                shadowFlags.sum(),
                centralTimeouts.sum(),
                centralUnavailable.sum(),
                centralConflicts.sum(),
                centralDegraded.sum(),
                centralBlocked.sum(),
                percentile(sorted, 0.50D),
                percentile(sorted, 0.95D),
                percentile(sorted, 0.99D)
        );
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    public record Snapshot(
            long requests,
            long successes,
            long failures,
            long rateLimited,
            long allows,
            long alerts,
            long deletes,
            long lateDeletes,
            long shadowFlags,
            long centralTimeouts,
            long centralUnavailable,
            long centralConflicts,
            long centralDegraded,
            long centralBlocked,
            long p50LatencyMs,
            long p95LatencyMs,
            long p99LatencyMs
    ) {
    }
}
