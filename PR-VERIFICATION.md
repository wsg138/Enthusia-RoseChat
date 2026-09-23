# Presence PR verification cleanup

The review branch now runs the existing hosted build on every PR base and verifies the exact head SHA. Existing LumaGuilds compile/packaging checks remain intact; no guild gameplay or RoseChat runtime implementation was changed during this cleanup.

Fresh local Gradle clean test shadowJar: {"tests":12,"failures":0,"errors":0,"skipped":0}. This PC has JDK 22 rather than JDK 21, so the local-only initialization script selected JDK 22 and release 21; the repository and hosted workflow continue to use their unchanged JDK 21 toolchain. This does not claim hosted success or live 26.x compatibility.

The separate original working directory contains pre-existing uncommitted compatibility edits; they were preserved and not silently included in this PR.
