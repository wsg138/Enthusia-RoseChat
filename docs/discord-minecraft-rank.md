# Linked Discord sender ranks

Discord-to-Minecraft chat uses a gold `[D]` marker followed by the linked
Minecraft account's existing `{prefix}` and nickname. Unlinked senders use a
neutral gray nickname and do not receive a Minecraft rank identity.

`discord_linked` is supplied by the DiscordSRV account-link lookup, not a Discord
role or user-supplied message. Existing channel permissions and delivery remain
unchanged. PlaceholderAPI/LuckPerms must provide the existing prefix.

Existing installations must merge the `from-discord` and `discord-player` text
entries from `custom-placeholders.yml`; plugin defaults do not overwrite existing
files. Channels should use:

```yaml
discord-to-minecraft: '{from-discord}{discord-player}{separator}{message}'
```

For staff channels retain `{channel-prefix}` after `{from-discord}`. Keep hover,
prefix, and unrelated entries unchanged. Validate linked online/offline and
unlinked accounts on a test server before production acceptance.
