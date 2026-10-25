# Cloudscape release candidate

Canonical sources: src/main. The accepted baseline is 0.5.7-sharp.1; see
docs/DEVELOPMENT.md. Archive/compat/experiments are historical evidence.

The user froze cloud shapes and rendering quality for this release. Keep Java,
GLSL, defaults, quality, lighting and sample budgets identical unless a later
human instruction explicitly changes that scope. Video-only maximum settings
belong in an isolated filming profile; do not migrate them into the user profile.

Use build.ps1 and check.ps1 with explicit dependency roots. Run GPU work
sequentially. Never terminate a user's Minecraft. Keep isolated validation
worlds and credentials separate. Preserve all failed measurements; do not relax
performance gates after seeing candidate results. Keep development validation
classes, worlds, launchers, third-party binaries and research packs out of JARs.

Before replacing the installed JAR, verify backups and test rollback in a fixture.
Preserve exact user configuration and other mods. Report limitations honestly.
Do not publish a release, create an external repository or send messages without
human authorization for that external action.
