package dev.rosewood.rosechat.chat.channel;

import dev.rosewood.rosechat.hook.channel.ChannelProvider;
import dev.rosewood.rosechat.message.RosePlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelConcurrencyTest {

    @Test
    void membershipCollectionRemainsValidDuringConcurrentUpdates() throws Exception {
        TestChannel channel = new TestChannel();
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            players.add(UUID.randomUUID());
        }

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 4096; i++) {
                UUID player = players.get(i % players.size());
                futures.add(executor.submit(() -> {
                    start.await();
                    channel.addMember(player);
                    channel.getMemberCount();
                    channel.getMembers().forEach(UUID::hashCode);
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(players.size(), channel.getMemberCount());
        assertEquals(players.size(), channel.getMembers().stream().distinct().count());
    }

    private static final class TestChannel extends Channel {

        private TestChannel() {
            super((ChannelProvider) null);
        }

        private void addMember(UUID uuid) {
            this.members.addIfAbsent(uuid);
        }

        @Override
        public void send(ChannelMessageOptions options) {
        }

        @Override
        public boolean canJoinByCommand(RosePlayer player) {
            return true;
        }

        @Override
        public List<UUID> getMembers() {
            return this.members;
        }

        @Override
        public String getId() {
            return "test";
        }

        @Override
        public List<String> getServers() {
            return List.of();
        }
    }
}
