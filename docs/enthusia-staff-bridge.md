# EnthusiaStaff bridge

RoseChat publishes `RoseChatStaffService` through Bukkit's `ServicesManager` while the plugin is enabled. EnthusiaStaff discovers that service and installs one moderation bridge. RoseChat owns the API classes at runtime; consumers must compile against the contract without packaging a second copy.

The bridge covers:

- reading and changing an online player's active channel;
- toggling between the configured staff and global channels;
- mute checks before channel or private-message delivery;
- staff-only routing for restricted senders;
- private-message capture after successful delivery;
- per-recipient channel visibility;
- join and quit visibility.

If no bridge is installed, RoseChat keeps its normal behavior. Only one bridge owner may be active at a time, and closing an old registration cannot remove a newer owner. Decision and visibility callback failures fail closed rather than allowing a moderation or visibility bypass. Capture failures are logged after delivery.

## Channel configuration

The consumer supplies the staff channel, global channel, and any channels that should be classified as private. The default Enthusia configuration uses `staff` and `global`, matching the bundled `channels.yml`.

The staff channel must exist before players can toggle into it. Channel changes still follow RoseChat's normal permission, join-condition, and channel-change event checks.

## Message handling

RoseChat checks external mute state before running filter actions. It then applies its normal local filters before the bridge makes the final broadcast or private-message decision. A blocked decision is returned to the sender before console, spy, Discord, network, or player delivery. A staff-only decision is redirected into the configured staff channel and does not continue through the original destination.

Private messages are captured only after the target delivery succeeds. Incoming network messages retain the original sender UUID so moderation and capture use the same identity on every server.

## Building

RoseChat requires Java 21. LumaGuilds is a compile-only dependency and is not available from a public Maven repository. Normal builds use the checked-in minimal compile contract under `src/lumaGuildsApi`; those classes are never packaged. To validate against a complete LumaGuilds build, put its jar at `libs/LumaGuilds-2.1.0.jar` or provide its path through `LUMAGUILDS_JAR`.

Run the complete local check with:

```text
./gradlew clean test shadowJar
```

The shaded RoseChat jar must contain `dev/rosewood/rosechat/api/staff`, must not contain EnthusiaStaff implementation classes, and must not bundle Bukkit or the LumaGuilds dependency.
