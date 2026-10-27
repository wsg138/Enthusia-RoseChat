# Linked Discord sender ranks

Discord-to-Minecraft chat uses a gold `[D]` marker followed by the linked
Minecraft account's existing `{prefix}` and nickname. Unlinked senders use a
neutral gray nickname and do not receive a Minecraft rank identity.

`discord_linked` is supplied by the DiscordSRV account-link lookup, not a Discord
role or user-supplied message. Existing channel permissions and delivery remain
unchanged. PlaceholderAPI/LuckPerms must provide the existing prefix.

On reload, missing `from-discord`/`discord-player` entries or missing text sections
are filled from the bundled defaults. Text sections containing only the exact old
bundled default are upgraded. Customized text (including explicitly empty text),
extra conditions, hover/click entries and unrelated placeholders are retained.
Custom installations still need an operator-reviewed manual format change.
Channels should use:

```yaml
discord-to-minecraft: '{from-discord}{discord-player}{separator}{message}'
```

For staff channels retain `{channel-prefix}` after `{from-discord}`. Keep hover,
prefix, and unrelated entries unchanged. Validate linked online/offline and
unlinked accounts on a test server before production acceptance.
