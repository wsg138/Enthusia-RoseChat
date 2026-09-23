package dev.rosewood.rosechat.api.event;

import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired once per eligible viewer after presence visibility and template conditions.
 * Replacements are parsed by RoseChat with the original subject/viewer context.
 * An empty default audience is never expanded by this event.
 */
public final class PresenceMessageEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Player viewer;
    private final String kind;
    private List<String> lines;
    private boolean cancelled;

    public PresenceMessageEvent(Player player, Player viewer, String kind, List<String> lines) {
        this.player = Objects.requireNonNull(player);
        this.viewer = Objects.requireNonNull(viewer);
        if (!"join".equals(kind) && !"quit".equals(kind))
            throw new IllegalArgumentException("Presence kind must be join or quit");
        this.kind = kind;
        this.lines = List.copyOf(lines);
    }

    public Player getPlayer() { return this.player; }
    public Player getViewer() { return this.viewer; }
    public String getKind() { return this.kind; }
    public List<String> getLines() { return this.lines; }
    public void setLines(List<String> lines) { this.lines = List.copyOf(lines); }
    @Override public boolean isCancelled() { return this.cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
