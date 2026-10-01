# Catbud settings product context

## Confirmed user requirements

Audience: players using Catbud's Minecraft client addons. Their task is configuring installed addons in one shared native settings panel.

The user approved implementation of the complete panel structure discussed in this chat. The core opens with default Z, allows rebinding through Minecraft Controls, and is also exposed through optional Mod Menu integration. Only installed registered addons appear. Magic Tower is the first addon.

Remove the Magic Tower `/catbud` command family and F8 Debug mode. Retain the existing target-route key and Shift modifier. Do not expose performance tuning in settings.

The native EntityCulling config provider in `../../references/EntityCulling/src/main/java/dev/tr7zw/entityculling/config/ConfigScreenProvider.java` and the user's supplied image `C:/Users/User/.codex/attachments/0c6bd045-ff73-441d-a86d-d6717fb7ec56/image-1.png` are the explicit visual authority. The user rejected the earlier full-screen On/Off-button layout. Match the bounded translucent dark frame, left title, top tabs, left square check/cross toggles, concise single-line rows with explanatory tooltips, integrated scrolling, Done on the left and Reset on the right. Keep Apply and Cancel as secondary footer actions to preserve approved draft semantics. The RGB picker shares the same framed material. Wide layouts may include actual Minecraft item-texture tab icons; compact layouts keep readable text tabs.

Core pages: General (key-binding entry and language following Minecraft) and About (core version and registered addon versions).

Magic Tower categories: General, Radar, ESP, Routes, Hints, Status. The full declarative schema is authoritative for the approved settings inventory. Include independent display and route controls, target-type filters, sizes, opacity, distance display filters, colors and thickness. Status contains live session/target information and immediate connection/floor operations. Exclude internal terrain scan counters and performance/debug information. All permanent and floor switches use check/cross controls. Opacity is presented and entered as a percentage, while storage retains its normalized value.

Saved preferences survive restarting; force/stop/auto session operations and floor overrides do not persist. Exploration resets to the saved default on a new floor/session. Changing the saved exploration default also updates this floor. Cancel does not undo immediate status operations.

Apply saves and updates runtime; Done applies and closes; Cancel/Escape discards pending drafts. Category reset changes only its category's draft. Drafts survive category/addon changes, resize, Controls and the color picker. Numeric/color inputs are validated; dependent controls are disabled without erasing their preferences. English and Traditional Chinese follow the game language.

## Surface mode and constraints

Operate mode. Scanability, native interaction and reliable configuration outrank visual expression. The user fixed the structure and reference and has approved implementation; no alternative concept round is needed. Existing scan/detection/pathfinding rules remain internal. Only loaded/known targets can be shown; changing display distance does not discover terrain.

Delivery is a Java 25 / Fabric 26.2 client mod. Core is a reusable separate Gradle/mod module embedded in Magic Tower's release jar. Other addons register declarative settings with the same singleton core.

## Evidence and boundaries

Unit tests cover config validation/persistence/failure behavior and Magic Tower setting dependencies. An isolated opt-in verification source set captures the actual Minecraft GUI and checks its interactions; it is excluded from release jars. Windows system-information warnings seen in the development client do not validate or invalidate addon behavior.
