# Catbud Core

Shared client-side settings for Minecraft Fabric 1.21.11 (Java 21), 26.2 and 26.3 (Java 25). Magic Tower builds this Gradle subproject and embeds its mod jar, so users install just the Magic Tower release jar. Other addons must depend on `catbud_core >=1.0.0` and use the core built for the same Minecraft target rather than registering another settings key.

## Register an addon

Call `CatbudCore.register(SettingsAddon)` from the addon's client initializer. Provide a stable unique id, translated name, version, categories, declarative `Setting` definitions, a `SettingsStore`, and a supplier for live status rows. An optional `extraRows` category function adds runtime information alongside settings (for example, an addon's current target key). Each setting declares its type, default, limits and an availability predicate. The predicate disables its widget without deleting the stored preference.

The screen discovers registered addons; no core changes are needed to add a category or addon. Labels use `setting.<addon-id>.<setting-id>`, descriptions append `.description`, and category names use `category.catbud_core.<category-id>`. Place the addon's translations in its own resources. Add new category translations there too.

Use `CatbudCore.configPath(id)` for `config/catbud/addons/<id>.json`. The core's own document is `config/catbud/core.json`. Current documents have `configVersion: 1` and a `values` object. Missing fields receive defaults; invalid individual values are validated independently. Unknown fields are retained. Invalid documents are backed up before defaults are used.

`SettingsStore.current()` returns a copy. Publish the callback's validated snapshot to your runtime after a successful save. The callback must be quick, execute on the client thread and update or invalidate any affected runtime caches. Atomic replacement protects the file; save failures do not publish a new runtime snapshot. Across several addons, each successful save is committed separately, so a later failure leaves earlier saves applied and keeps all drafts available for retry.

Status actions operate immediately and are never stored as preferences. Supply availability checks and explanatory text for unavailable actions. Do not encode session state, world positions or floor overrides in saved values.

For an immediate boolean action, provide the optional `StatusRow.checked` supplier. The core renders it with the same left check/cross control as permanent settings; availability and the checked state remain live.

Mod Menu's settings button is registered by Catbud Core and opens the shared settings screen for all registered addons. Mod Menu is optional.

## Screen behavior

- Z opens settings during gameplay, with a rebindable native Minecraft key mapping. Screens such as chat consume input without opening settings.
- Addon and category tabs, native scrolling rows, search across the selected addon, keyboard-focusable controls, tooltips and category reset.
- Apply saves drafts; Done saves and returns; Cancel/Escape returns without saving drafts. Drafts survive resizing, visiting Controls and opening the RGB/hex color picker.
- RGB and hex input share the color preview. Canceling the picker leaves the settings draft unchanged.
- Language follows Minecraft. No performance or debug controls are exposed.

## Build and tests

Use JDK 25 to run Gradle. `gradlew.bat :projects:catbud_core:26.2:assemble` packages one target without tests; substitute `1.21.11` or `26.3` as needed. `gradlew.bat :projects:catbud_core:build` builds and tests all Core targets. Only installable Core release jars are in the repository root `out/` directory. Source jars stay in `versions/<minecraft>/build/libs/`. The optional settings harness remains on 26.2 through `gradlew.bat -PverifySettings :projects:magic_tower:26.2:runClient`. See the [root build guide](../../README.md) for source sharing and version adapters.


