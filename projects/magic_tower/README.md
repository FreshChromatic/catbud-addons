# Catbud Magic Tower

Fabric client mod for Minecraft 1.21.11, 26.2 and 26.3. The server's existing Bossbars and messages control the session; no server-side mod is required.

## Build and install

Use JDK 25 for Gradle. Run `gradlew.bat :projects:magic_tower:26.2:assemble` to package one target without tests; substitute `1.21.11` or `26.3` as needed. Only installable release jars are in the repository root `out/` directory. Source jars stay in `versions/<minecraft>/build/libs/`. Install the jar and Fabric API matching your game. The 1.21.11 jar targets Java 21; modern jars target Java 25. Catbud Core for the same target is embedded. Mod Menu is optional. Source jars are for development. See the [root build guide](../../README.md) for all-version builds and release collection.

## Settings

Press **Z** during gameplay to open the shared **Catbud Addons Settings** panel. Rebind it in Minecraft Controls. Chat and other screens do not trigger the settings key. In Mod Menu, Catbud Core's settings button opens the same panel. The previous client command family and Debug hotkey have been removed.

- **Core**: the settings-key Controls entry, language following Minecraft, core version and registered addon versions.
- **Magic Tower → General**: master enable switch and target-key Controls entry.
- **Radar**: portal/facility visibility, distance text, off-screen arrows, height indicators, icon/text scale, opacity, display distance, near-hide threshold, screen margin, height threshold and portal marker colors.
- **ESP**: copper chest/mushroom outlines, display distance, colors, opacity and thickness.
- **Routes**: master enable, automatic portal routes and per-portal switches, per-floor exploration default, manual route enable and selectable target types, per-type/exploration colors, opacity and thickness.
- **Hints**: crosshair information, coordinates, distance, rebound key hints, exploration count/priority and Chat/Actionbar/Off operation messages.
- **Status**: live scan/session/evacuation states, target and route counts, terrain statistics, immediate connection scan actions and floor-only exploration/portal overrides.

Search finds settings across the selected addon and shows their categories. Option explanations remain available in tooltips. Apply saves preferences and updates the addon; Done applies and closes; Cancel or Escape discards pending preferences. Reset affects only the current category's draft. The RGB/hex picker has its own Done/Cancel. Drafts survive changing tabs, resizing and visiting Controls.

Status actions take effect immediately; Cancel does not undo them. Force/stop/auto mode lasts for the connection, while floor overrides reset on a new floor/session. The saved exploration default is used after those resets. Changing that default also updates the current floor. Actions outside a world or while their required feature is disabled are unavailable.

Preferences are saved to `config/catbud/addons/magic_tower.json`; the core uses `config/catbud/core.json`. The previous automatic-portal preference is migrated from the legacy settings file on first use. Its Debug field is ignored. Invalid values fall back to validated defaults; malformed files are preserved with an `.invalid-<timestamp>` backup. Save errors are shown in the panel without discarding drafts. No performance settings are exposed. Display switches and manual target eligibility are independent.

The target-route key remains **`** by default; **Shift + that key** cycles aimed targets. Hints follow the actual rebound key.

## Behaviour

- A single Bossbar must contain both translation keys `plugins.venue_manager.room_queuing_distance` and `plugins.magic_tower.magic_tower` to start watching a queue.
- If the queue Bossbar changes to `plugins.venue_manager.venue_waiting_activate` or is replaced by a new Bossbar with that text, the mod waits for a teleport. Five seconds is an expected server delay, not a timeout.
- After the queue Bossbar disappears without a ready signal, the mod allows three seconds for a replacement or teleport packet. Once ready was observed, it allows 15 seconds after the ready Bossbar disappears. The session starts only after teleporting into `minecraft:custom`. Leaving that dimension or disconnecting cancels it.
- In a session, portal radar shows loaded nether and end portals, grouped by connected portal blocks. Chest ESP draws a screen-projected 3D outline around loaded `minecraft:copper_chest` and `minecraft:waxed_copper_chest` blocks, including double chests. A chest is excluded once the server changes it into another block.
- Server block and chunk updates refresh the markers automatically. Already loaded chunks are also rescanned periodically (nearby chunks about once per second), so terrain revealed after approaching it is discovered automatically.
- `plugins.magic_tower.waiting_layer_response` from a Bossbar, Title, Subtitle, Actionbar, or system chat clears old targets and starts a fresh scan.
- Exploration routes are enabled by default, configurable in Routes. They draw separate teal lines (default `#088DA5`) to every reachable, still blocked bedrock boundary in loaded terrain, without ranking the boundaries or assuming which bedrock will open. Routes update as terrain changes and work independently of the automatic portal setting.
- Automatic portal routes begin only after a portal has been discovered. Automatic end-portal routes additionally require the server's evacuation-room announcement. Their permanent preferences are in Routes and floor overrides are in Status.

The client can only find blocks in chunks sent by the server. If a chest changes while its chunk is unloaded and later returns with the same original block ID, the client cannot infer that unseen change.

## Verification

From the repository root, `gradlew.bat build` builds and tests all targets. `gradlew.bat assemble` only compiles and packages them. The optional 26.2 harness is available through `gradlew.bat -PverifySettings :projects:magic_tower:26.2:runClient`; it uses `versions/26.2/build/verification-client` and is excluded from release jars. See [Catbud Core](../catbud_core/README.md) for the addon registration API.


