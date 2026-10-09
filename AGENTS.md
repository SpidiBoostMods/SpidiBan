# SpidiBan project rules

- Author: SpidiBoost. Mod Menu name: spidiboost.SpidiBan. Java 21, Minecraft 1.21.4, Fabric; standalone except Fabric API.
- Publish to SpidiBoostMods/SpidiBan, using SpidiBoostDev. Preserve other GitHub accounts.
- JAR names: SpidiBan-1.21.4-<mod_version>.jar; metadata and filename versions must match.
- Run local checks, native client integration for UI/behavior changes, and Windows/macOS/Linux CI before publication. Compilation alone is not runtime proof.
- Increment mod_version, commit and push an annotated release tag; verify public manifest, downloaded identity and SHA-256.
- Shared updater copies are generated from the SpidiCard src/sharedTemplate sources; keep protocol and catalog identical across the four mods. Each relocated copy participates in one ObjectShare election.
- Never publish credentials, live instances, player results, logs, configs or launcher arguments. Keep licenses for upstream assets.
- Do not touch AdminTools or user recordings. Use isolated runtime verification.
- Update only installed family JARs after the game exits; preserve configs/results/other mods and backups. Hist addon removal is allowed only after its complete migration into SpidiBan.
