package dev.rosewood.rosechat.listener;

import dev.rosewood.rosegarden.utils.StringPlaceholders;

/** Only a DiscordSRV account link grants a Discord sender a Minecraft rank identity. */
final class DiscordIdentityPlaceholders {
    private DiscordIdentityPlaceholders() { }

    static StringPlaceholders.Builder add(StringPlaceholders.Builder builder, String nickname, boolean linked) {
        return builder.add("user_nickname", nickname).add("discord_linked", Boolean.toString(linked));
    }
}
