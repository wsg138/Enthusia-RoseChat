package dev.rosewood.rosechat.staff;

import dev.rosewood.rosechat.api.staff.BridgeRegistration;
import dev.rosewood.rosechat.api.staff.BroadcastContext;
import dev.rosewood.rosechat.api.staff.ChannelClassification;
import dev.rosewood.rosechat.api.staff.ChannelRecipientContext;
import dev.rosewood.rosechat.api.staff.ModerationDecision;
import dev.rosewood.rosechat.api.staff.PresenceContext;
import dev.rosewood.rosechat.api.staff.PrivateMessageContext;
import dev.rosewood.rosechat.api.staff.RoseChatModerationBridge;
import dev.rosewood.rosechat.api.staff.StaffChannelConfiguration;
import dev.rosewood.rosechat.api.staff.TransmissionContext;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

final class StaffBridgeCoordinator implements AutoCloseable {
    private static final String CALLBACK_FAILURE_FEEDBACK =
            "Your message could not be checked right now. Please try again shortly.";

    private final AtomicReference<InstalledBridge> installed;
    private final Logger logger;
    private volatile boolean closed;

    StaffBridgeCoordinator(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.installed = new AtomicReference<>();
    }

    synchronized BridgeRegistration install(
            String owner,
            StaffChannelConfiguration configuration,
            RoseChatModerationBridge bridge
    ) {
        if (this.closed) {
            throw new IllegalStateException("RoseChat staff service is closed");
        }
        Objects.requireNonNull(owner, "owner");
        String normalizedOwner = owner.trim();
        if (normalizedOwner.isEmpty()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (normalizedOwner.length() > 128 || normalizedOwner.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("owner contains unsupported characters or is too long");
        }

        InstalledBridge candidate = new InstalledBridge(
                normalizedOwner,
                Objects.requireNonNull(configuration, "configuration"),
                Objects.requireNonNull(bridge, "bridge")
        );
        InstalledBridge current = this.installed.get();
        if (current != null) {
            throw new IllegalStateException(
                    "RoseChat moderation bridge is already owned by " + current.owner()
            );
        }
        this.installed.set(candidate);

        return new Registration(candidate);
    }

    Optional<String> owner() {
        return Optional.ofNullable(this.installed.get()).map(InstalledBridge::owner);
    }

    Optional<StaffChannelConfiguration> configuration() {
        return Optional.ofNullable(this.installed.get()).map(InstalledBridge::configuration);
    }

    ChannelClassification classify(String channelId, String defaultStaffChannel) {
        Objects.requireNonNull(channelId, "channelId");
        String normalized = channelId.trim().toLowerCase(Locale.ROOT);
        StaffChannelConfiguration configuration = this.configuration().orElse(null);
        String staffChannel = configuration == null
                ? defaultStaffChannel
                : configuration.staffChannelId();
        if (normalized.equals(staffChannel.toLowerCase(Locale.ROOT))) {
            return ChannelClassification.STAFF;
        }
        if (configuration != null && configuration.privateChannelIds().contains(normalized)) {
            return ChannelClassification.PRIVATE;
        }
        return ChannelClassification.PUBLIC;
    }

    ModerationDecision enforceMute(TransmissionContext transmission) {
        InstalledBridge current = this.installed.get();
        if (current == null) {
            return ModerationDecision.allow();
        }
        return this.safeDecision(
                current,
                "enforceMute",
                () -> current.bridge().enforceMute(transmission)
        );
    }

    ModerationDecision beforeBroadcast(BroadcastContext broadcast) {
        InstalledBridge current = this.installed.get();
        if (current == null) {
            return ModerationDecision.allow();
        }
        return this.safeDecision(
                current,
                "beforeBroadcast",
                () -> current.bridge().beforeBroadcast(broadcast)
        );
    }

    ModerationDecision beforePrivateMessage(PrivateMessageContext message) {
        InstalledBridge current = this.installed.get();
        if (current == null) {
            return ModerationDecision.allow();
        }
        return this.safeDecision(
                current,
                "beforePrivateMessage",
                () -> current.bridge().beforePrivateMessage(message)
        );
    }

    void capturePrivateMessage(PrivateMessageContext context) {
        InstalledBridge current = this.installed.get();
        if (current == null) {
            return;
        }
        try {
            current.bridge().capturePrivateMessage(context);
        } catch (RuntimeException | LinkageError exception) {
            this.logCallbackFailure(current, "capturePrivateMessage", exception);
        }
    }

    boolean canReceiveChannelMessage(ChannelRecipientContext context) {
        InstalledBridge current = this.installed.get();
        if (current == null) {
            return true;
        }
        try {
            return current.bridge().canReceiveChannelMessage(context);
        } catch (RuntimeException | LinkageError exception) {
            this.logCallbackFailure(current, "canReceiveChannelMessage", exception);
            return false;
        }
    }

    boolean canRenderPresence(PresenceContext context) {
        InstalledBridge current = this.installed.get();
        if (current == null) {
            return true;
        }
        try {
            return current.bridge().canRenderPresence(context);
        } catch (RuntimeException | LinkageError exception) {
            this.logCallbackFailure(current, "canRenderPresence", exception);
            return false;
        }
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        this.installed.set(null);
    }

    private ModerationDecision safeDecision(
            InstalledBridge current,
            String callback,
            Supplier<ModerationDecision> invocation
    ) {
        try {
            ModerationDecision decision = invocation.get();
            if (decision != null) {
                return decision;
            }
            this.logger.warning(
                    "RoseChat moderation bridge callback " + callback
                            + " returned null for owner " + current.owner()
            );
        } catch (RuntimeException | LinkageError exception) {
            this.logCallbackFailure(current, callback, exception);
        }
        return ModerationDecision.block(CALLBACK_FAILURE_FEEDBACK);
    }

    private void logCallbackFailure(InstalledBridge current, String callback, Throwable exception) {
        this.logger.log(
                Level.WARNING,
                "RoseChat moderation bridge callback " + callback
                        + " failed for owner " + current.owner(),
                exception
        );
    }

    private record InstalledBridge(
            String owner,
            StaffChannelConfiguration configuration,
            RoseChatModerationBridge bridge
    ) {
    }

    private final class Registration implements BridgeRegistration {
        private final InstalledBridge bridge;

        private Registration(InstalledBridge bridge) {
            this.bridge = bridge;
        }

        @Override
        public String owner() {
            return this.bridge.owner();
        }

        @Override
        public boolean isActive() {
            return StaffBridgeCoordinator.this.installed.get() == this.bridge;
        }

        @Override
        public void close() {
            StaffBridgeCoordinator.this.installed.compareAndSet(this.bridge, null);
        }
    }
}
