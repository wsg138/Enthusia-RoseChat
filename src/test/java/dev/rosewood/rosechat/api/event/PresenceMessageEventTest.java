package dev.rosewood.rosechat.api.event;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresenceMessageEventTest {
    @Test
    void replacementIsDefensivelyCopiedAndMaySuppressLines() {
        PresenceMessageEvent event = new PresenceMessageEvent(player(), player(), "quit", List.of("default"));
        List<String> replacement = new ArrayList<>(List.of("custom"));
        event.setLines(replacement);
        replacement.clear();
        assertEquals(List.of("custom"), event.getLines());
        event.setLines(List.of());
        assertTrue(event.getLines().isEmpty());
    }

    @Test
    void rejectsNullSubjectsViewersAndLines() {
        Player subject = player();
        Player viewer = player();
        assertThrows(NullPointerException.class, () -> new PresenceMessageEvent(null, viewer, "join", List.of()));
        assertThrows(NullPointerException.class, () -> new PresenceMessageEvent(subject, null, "join", List.of()));
        assertThrows(NullPointerException.class, () -> new PresenceMessageEvent(subject, viewer, "join", null));
        PresenceMessageEvent event = new PresenceMessageEvent(subject, viewer, "join", List.of("default"));
        assertThrows(NullPointerException.class, () -> event.setLines(null));
        assertEquals(List.of("default"), event.getLines());
    }
    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> null);
    }

    @Test
    void retainsSubjectViewerAndOriginalLinesUntilReplaced() {
        Player subject = player();
        Player viewer = player();
        List<String> defaults = new ArrayList<>(List.of("first", "second"));
        PresenceMessageEvent event = new PresenceMessageEvent(subject, viewer, "join", defaults);
        defaults.clear();
        assertSame(subject, event.getPlayer());
        assertSame(viewer, event.getViewer());
        assertEquals(List.of("first", "second"), event.getLines());
        assertThrows(UnsupportedOperationException.class, () -> event.getLines().clear());
        event.setLines(List.of("custom"));
        assertEquals(List.of("custom"), event.getLines());
    }

    @Test
    void supportsCancellationAndValidatesPresenceKind() {
        Player subject = player();
        Player viewer = player();
        PresenceMessageEvent event = new PresenceMessageEvent(subject, viewer, "quit", List.of("default"));
        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
        assertSame(PresenceMessageEvent.getHandlerList(), event.getHandlers());
        assertThrows(IllegalArgumentException.class,
                () -> new PresenceMessageEvent(subject, viewer, "chat", List.of()));
    }
}
