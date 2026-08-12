package dev.rosewood.rosechat.chat.task;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.chat.log.ConsoleMessageLog;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

public class ChatLogTask extends BukkitRunnable {

    private final ConsoleMessageLog log;
    private final File file;
    // Serializes complete flushes: the repeating async task and save()'s
    // synchronous run() can overlap, and two interleaved flushes would append
    // batches out of order (or a failed older batch could retry after a newer one).
    private final Object flushLock = new Object();

    public ChatLogTask(RoseChat plugin, ConsoleMessageLog log) throws IOException {
        this.log = log;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        String date = sdf.format(new Date());
        Path folder = Files.createDirectories(Path.of(plugin.getDataFolder() + "/log/"));
        this.file = Files.createFile(folder.resolve(date + ".log")).toFile();

        this.runTaskTimerAsynchronously(plugin, 0L, 30L * 20L);
    }

    @Override
    public void run() {
        synchronized (this.flushLock) {
            // Snapshot under the log's lock, then write outside of it: chat
            // handlers (main + async) add to this log concurrently, and
            // iterating/clearing the live list raced with those adds.
            List<String> snapshot;
            synchronized (this.log.getMessages()) {
                snapshot = new ArrayList<>(this.log.getMessages());
                this.log.getMessages().clear();
            }

            try (FileWriter writer = new FileWriter(this.file, true)) {
                for (String s : snapshot)
                    writer.write(s + "\n");
            } catch (IOException e) {
                e.printStackTrace();
                Bukkit.getLogger().warning("An error occurred while writing the chat log.");
                // Don't lose the batch: put it back at the front so the next run retries.
                synchronized (this.log.getMessages()) {
                    this.log.getMessages().addAll(0, snapshot);
                }
            }
        }
    }

    // Run once to save anything left over.
    public void save() {
        this.run();
        this.cancel();
    }

}
