package dev.rosewood.rosechat.api.staff;

public interface BridgeRegistration extends AutoCloseable {
    String owner();

    boolean isActive();

    default boolean renderPresence(PresenceContext context) {
        return false;
    }

    @Override
    void close();
}
