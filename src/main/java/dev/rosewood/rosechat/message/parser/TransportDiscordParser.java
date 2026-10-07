package dev.rosewood.rosechat.message.parser;

import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosechat.message.MessageDirection;
import dev.rosewood.rosechat.message.RoseMessage;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosechat.message.contents.MessageContents;
import dev.rosewood.rosechat.message.tokenizer.MessageTokenizer;
import dev.rosewood.rosechat.message.tokenizer.Tokenizers;

/**
 * Discord-oriented parser that deliberately excludes Discord-provider lookups.
 *
 * <p>The legacy DiscordSRV parser resolves member/channel/custom-emoji mentions through
 * {@code DiscordChatProvider}. The Enthusia transport must remain usable after DiscordSRV is
 * removed, so this parser keeps RoseChat formatting/markdown semantics while leaving external
 * Discord identity/presentation decisions to StaffBot.</p>
 */
public final class TransportDiscordParser implements MessageParser {

    public static final MessageParser INSTANCE = new TransportDiscordParser();

    private static final Tokenizers.TokenizerBundle SAFE_TO_DISCORD = new Tokenizers.TokenizerBundle(
            "transport_to_discord",
            Tokenizers.TO_DISCORD_URL,
            Tokenizers.TO_DISCORD_SPOILER
    );

    private TransportDiscordParser() {
    }

    @Override
    public MessageContents parse(RoseMessage message, RosePlayer viewer, String format) {
        if (Settings.USE_MARKDOWN_FORMATTING.get()) {
            return MessageTokenizer.tokenize(
                    message,
                    viewer,
                    format,
                    MessageDirection.MINECRAFT_TO_DISCORD,
                    SAFE_TO_DISCORD,
                    Tokenizers.DISCORD_FORMATTING_BUNDLE,
                    Tokenizers.DEFAULT_DISCORD_BUNDLE
            );
        }
        return MessageTokenizer.tokenize(
                message,
                viewer,
                format,
                MessageDirection.MINECRAFT_TO_DISCORD,
                SAFE_TO_DISCORD,
                Tokenizers.DEFAULT_DISCORD_BUNDLE
        );
    }
}
