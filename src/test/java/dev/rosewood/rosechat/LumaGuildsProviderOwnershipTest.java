package dev.rosewood.rosechat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumaGuildsProviderOwnershipTest {

    @Test
    void roseChatDoesNotPackageItsOwnLumaGuildsProvider() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("dev.rosewood.rosechat.hook.channel.lumaguilds.LumaGuildsChannelProvider")
        );
    }

    @Test
    void buildDoesNotCompileAgainstLumaGuildsInternals() throws Exception {
        String build = Files.readString(Path.of("build.gradle"));
        assertFalse(build.contains("LUMAGUILDS_JAR"));
        assertFalse(build.contains("src/lumaGuildsApi"));
        assertFalse(build.contains("compileOnly files(lumaGuildsJar)"));
    }

    @Test
    void channelRegenerationPreservesDynamicallyRegisteredProviders() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/rosewood/rosechat/manager/ChannelManager.java"
        ));
        int generateStart = source.indexOf("public void generateChannels()");
        int generateEnd = source.indexOf("public void generateChannel(", generateStart);
        assertTrue(generateStart >= 0 && generateEnd > generateStart);
        String generateBody = source.substring(generateStart, generateEnd);
        assertFalse(generateBody.contains("this.channels.clear()"));

        int disableStart = source.indexOf("public void disable()");
        int disableEnd = source.indexOf("public void register(ChannelProvider", disableStart);
        assertTrue(disableStart >= 0 && disableEnd > disableStart);
        String disableBody = source.substring(disableStart, disableEnd);
        assertTrue(disableBody.contains("this.channels.clear()"));
    }
}
