# LumaGuilds compile contract

These sources contain only the LumaGuilds types and methods used by RoseChat's channel hook. They let a clean checkout compile without downloading or packaging the full LumaGuilds plugin. The contract was checked against LumaGuilds commit `70bc6d055b22d1f824edd89ef57280f457c998fc` and the corresponding runtime jar.

Gradle uses a complete jar instead when `LUMAGUILDS_JAR` is set or `libs/LumaGuilds-2.1.0.jar` exists. Always run that full-jar build after changing the hook or updating LumaGuilds. The shaded RoseChat jar must not contain any `net/lumalyte/lg` classes.
