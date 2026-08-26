package dev.rosewood.rosechat.api.staff;

public interface BridgeRegistration extends AutoCloseable {
    String owner();

    boolean isActive();

    @Override
    void close();
}
