package dev.rosewood.rosechat.listener;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.manager.DebugManager;
import dev.rosewood.rosechat.manager.PlaceholderManager;
import dev.rosewood.rosechat.message.MessageDirection;
import dev.rosewood.rosechat.message.PermissionArea;
import dev.rosewood.rosechat.message.RoseMessage;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosechat.message.tokenizer.MessageTokenizer;
import dev.rosewood.rosechat.message.tokenizer.Tokenizers;
import dev.rosewood.rosechat.message.tokenizer.composer.ChatComposer;
import dev.rosewood.rosechat.placeholder.ConditionManager;
import dev.rosewood.rosechat.placeholder.CustomPlaceholder;
import dev.rosewood.rosegarden.config.CommentedFileConfiguration;
import dev.rosewood.rosegarden.config.RoseConfig;
import dev.rosewood.rosegarden.config.RoseSetting;
import dev.rosewood.rosegarden.hook.PlaceholderAPIHook;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real nested tokenizer/condition/composer path; external plugin boundaries are mocked. */
class DiscordRankRenderingTest {

    @Test void linkedSenderResolvesNestedMinecraftPrefix() {
        assertEquals("[D] [Mod] Linked: hello", render(true, "Linked"));
    }

    @Test void unlinkedSenderDoesNotAcquireRankEvenWithRankPlaceholderAvailable() {
        assertEquals("[D] Unlinked: hello", render(false, "Unlinked"));
    }

    private static String render(boolean linked, String nickname) {
        RoseChat plugin = mock(RoseChat.class);
        RoseChatAPI api = mock(RoseChatAPI.class);
        PlaceholderManager manager = mock(PlaceholderManager.class);
        RosePlayer sender = mock(RosePlayer.class);
        RoseMessage message = mock(RoseMessage.class);
        var logger = mock(java.util.logging.Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        RoseConfig config = mock(RoseConfig.class);
        when(config.get(any(RoseSetting.class))).thenAnswer(invocation ->
                ((RoseSetting<?>) invocation.getArgument(0)).getDefaultValue());
        when(plugin.getRoseConfig()).thenReturn(config);
        when(plugin.getManager(DebugManager.class)).thenReturn(mock(DebugManager.class));
        when(api.getPlaceholderManager()).thenReturn(manager);
        when(sender.getName()).thenReturn(nickname);
        when(sender.getRealName()).thenReturn(nickname);
        when(sender.getDisplayName()).thenReturn(nickname);
        when(message.getSender()).thenReturn(sender);
        when(message.getLocation()).thenReturn(PermissionArea.NONE);
        when(message.getPlayerInput()).thenReturn("hello");
        when(message.getPlaceholders()).thenReturn(DiscordIdentityPlaceholders.add(
                StringPlaceholders.builder().add("vault_rank", "mod"), nickname, linked).build());

        try (var pluginStatic = mockStatic(RoseChat.class);
             var apiStatic = mockStatic(RoseChatAPI.class);
             var papiStatic = mockStatic(PlaceholderAPIHook.class);
             var bukkitStatic = mockStatic(org.bukkit.Bukkit.class)) {
            pluginStatic.when(RoseChat::getInstance).thenReturn(plugin);
            apiStatic.when(RoseChatAPI::getInstance).thenReturn(api);
            papiStatic.when(PlaceholderAPIHook::enabled).thenReturn(false);
            bukkitStatic.when(org.bukkit.Bukkit::getBukkitVersion).thenReturn("1.21.11-R0.1-SNAPSHOT");
            var yaml = CommentedFileConfiguration.loadConfiguration(new InputStreamReader(
                    Objects.requireNonNull(DiscordRankRenderingTest.class.getResourceAsStream(
                            "/custom-placeholders.yml")), StandardCharsets.UTF_8));
            for (String id : new String[] {"discord-player", "from-discord", "prefix"}) {
                CustomPlaceholder placeholder = new CustomPlaceholder(id);
                var section = yaml.getConfigurationSection(id);
                // Text rendering is the contract here; hover ItemStacks require a real Paper server.
                for (String location : new String[] {"text"}) {
                    var value = section.getConfigurationSection(location);
                    placeholder.add(location, ConditionManager.getCondition(value,
                            value.getString("condition")).parseValues());
                }
                when(manager.getPlaceholder(id)).thenReturn(placeholder);
            }
            var contents = MessageTokenizer.tokenize(message, null,
                    "{from-discord}{discord-player}: {message}", MessageDirection.DISCORD_TO_MINECRAFT,
                    new Tokenizers.TokenizerBundle("rank-test", Tokenizers.ROSECHAT_PLACEHOLDER,
                            Tokenizers.COLOR, Tokenizers.FORMAT, Tokenizers.TEXT));
            String rendered = contents.build(ChatComposer.plain());
            verify(logger, never()).warning(anyString());
            return rendered;
        }
    }
}
