package dev.rosewood.rosechat.chat.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConsoleMessageLog {

    protected final List<String> messages;

    public ConsoleMessageLog() {
        // Chat can be dispatched from multiple threads (e.g. InteractiveChat's
        // async redispatched events vs. main-thread chat), so the log must be
        // thread-safe. Compound operations on callers must still synchronize
        // on this list to be atomic.
        this.messages = Collections.synchronizedList(new ArrayList<>());
    }

    public void addMessage(String message) {
        this.messages.add(message);
    }

    public String getLastMessage() {
        if (this.messages.isEmpty())
            return null;

        return this.messages.get(this.messages.size() - 1);
    }

    public void removeLastMessage() {
        if (this.messages.isEmpty())
            return;

        this.messages.remove(this.messages.size() - 1);
    }

    public List<String> getMessages() {
        return this.messages;
    }

}
