# Public warps implementation status

2026-10-17 release update: all Minecraft modules and the cloud-service package now declare version 1.0.3. Earlier version numbers below describe historical builds and deployments; changing the local release version does not deploy or install it.

The user authorized implementation after approving the design decisions. The visual authority is the supplied SchematicPreview screenshot and the inspected browser sources, not Core's settings tabs. Runtime dependencies exclude malilib, litematica and SchematicPreview.

## Current evidence

- Four targets compile and package: 1.21.11, 26.1.2, 26.2, 26.3.
- Local Node 24 HTTP/SQLite service tests exercise two HTTP clients, real snapshot serialization/download, explicit pass rejection, authoritative identity matching and conflicting preview preservation. Test verification is injected; these tests do not prove a live Minecraft teleport.
- A read-only real Catbud API probe for catbud_net returned root uuid/name/pass, with pass=true and UUID matching the Mojang lookup. Neko_1212 returned 404 during earlier investigation.
- Isolated 1.21.11, 26.1.2, 26.2 and 26.3 verification clients have produced native browser/search/Traditional Chinese/compact screenshots with synthetic block snapshots. No third-party schematic mods are installed. Traditional Chinese native browser captures have been visually inspected for all four targets; the 26.2 compact capture was also reviewed at 854×480.
- Latest four-target builds pass twelve Java tests per target. The local service passes seven tests, including cached negative verification, a daily verification budget, age-derived ETag invalidation, upstream response limits and administrator preview lifecycle.
- Cache writes run on a dedicated ordered executor; switching sessions snapshots the previous cache and stopping the game flushes queued writes. Retry queues rotate failed reports and back off from one to thirty minutes. Preview digests and the thirty-minute per-target cooldown persist across restarts.
- Retry-After accepts bounded seconds or HTTP dates. Malformed hints preserve exponential backoff. Cache tests cover null/corrupt entries, original-file preservation and older cache defaults. Broken local previews are discarded individually so a shared replacement can download.
- Administrator approval preserves one previous snapshot per warp; rollback, removal and candidate rejection are available locally. Candidates expire after seven days during cleanup. Previous/candidate previews are not exposed through public download routes.
- User agreed to test on the real server and provide evidence. TESTING.md covers installation, opt-in bounded local protocol traces, success/cancellation/same-location/short-distance/cross-dimension/rapid-command cases and separate-instance sharing.
- The latest isolated 26.2 native run completed successfully with traceTeleport enabled and wrote the format-1 enabled event to the local JSONL file. This proves configuration/writer integration, not real-server packet events. Latest release JARs were rebuilt afterwards for all four targets.
- Asynchronous preview requests now capture immutable source/version identities. An old response cannot overwrite a newer local capture, a newer shared version, a manual-refresh replacement request or a different session. A regression test covers source replacement; all four targets were rebuilt after this fix. No layout changes were introduced.

## Completion gates still open

- Live Catbud command suggestions: whether the returned list is complete and whether prefixes are needed.
- Actual outgoing command and incoming dual translated components, including correlation after rapid commands.
- Successful arrival: the present conservative heuristic requires dual launch signals, a matching teleport Bossbar removal, a subsequent server position correction and loaded destination. Negative/cancel messages, death and disconnection invalidate it. This is not an authoritative server completion receipt and needs live evidence for same-location, short-distance, cross-dimension, cancellation and unrelated corrections.
- Shared preview from actual live capture, client qualification, service verification and a second Minecraft client download (HTTP-only sharing and synthetic native rendering are separately verified).
- Shared descriptions/tags are reserved for the user's future metadata phase; their editing flow is not a first-release requirement.
- Repeat relevant screenshot review if further layout changes are introduced.
- Workers/D1 integration only after local completion, per user instruction.

## Source reference

SchematicPreview source was read for measured grid/selection/layout behavior. Its all-rights-reserved source has not been copied into the addon. Rendering uses independently implemented vanilla baked block geometry and entity models; the latest revision uses a shared perspective/depth scene rather than the original CPU projection. Block entities are excluded, fluids and biome tint are simplified. References/malilib is a different API generation from the one referenced by the local Litematica/SchematicPreview tree, so direct source transplantation would need explicit conversion. No LGPL code has been transplanted yet; if introduced later, record origin and preserve required notices.

The planning document remains at docs/public-warps-plan.md (the repository's pre-existing ignore rules ignore docs). The implementation goal is unfinished; preliminary builds are not final delivery.

## Real-server rapid-switch investigation

The user supplied the actual 26.2 instance's public-warps-teleport.jsonl and warp cache. Normal observed successful attempts contain both launch keys, position correction, Bossbar removal and one arrival count. Multiple rapid maiyaWEE_77 → Yurui0905 replacements contain the first target's launch keys followed by superseded/second-command events, but no second translated launch messages or arrival count. The next command's starting position agrees with the user's report that the second teleport actually completed. The cache already contains Yurui0905's verified UUID, but has no personal arrival or local snapshot for that UUID. Earlier logs only recorded qualified position corrections, so absence of a correction log does not prove the server sent none.

The missing handler was Bossbar progress updates; add/name/remove were previously the only handled operations. The fix retains dual launch evidence only from a still-live, qualified attempt replaced within five seconds, then requires a fresh matching Bossbar progress/name update before qualifying the new target. Sending another command alone cannot qualify or count it. Old correction/removal evidence is cleared, and fresh position correction, Bossbar completion and loaded destination remain required. Cancelled, stale and single-channel attempts cannot be reused. Regression tests cover rapid replacement and these negative cases; all four targets rebuilt successfully. This is a protocol-informed fix, not yet proof that the real server emits the assumed progress update sequence.

Tracing now includes Bossbar progress and position corrections even before qualification, so a repeat rapid-switch test can resolve the remaining uncertainty. Existing user caches/logs were read only; missed visits were not backfilled and no historical snapshot was fabricated. Next required evidence is the revised trace and actual outcome for the same two-target switch. Workers/D1 remains deferred until local acceptance.

The user repeated the switch with the fix and reported success. The updated real trace's operation 1791706296811 has the Yurui0905 command, matching Bossbar progress updates, qualified destination corrections, matching removal and exactly one arrival_counted event, without needing new translated launch text. The actual user cache has that UUID's personal count=1, a saved preview and no pending reports. This verifies the reported rapid two-target replacement case on 26.2; it does not substitute for remaining cross-dimension, exhaustive suggestions or separate-client sharing acceptance.

The saved preview was decompressed and inspected: width/depth=10, height=16, 1600 block indices and 53 palette states. A read-only query of the user's running local service also returns Yurui0905 as verified_report with an official snapshotHash. Thus this case's local capture and shared-service recording are confirmed, beyond only a UI success report.

## 15-cube, entity and camera update (2026-10-11)

The user approved hover orbit, single-click selection with right-side interior entry while hovering the selected tile, reverse exit on leaving, and unchanged double-click teleport. The reference browser layout remains intact; no schematic mod runtime dependencies were added.

- Production snapshots use schema v2: 15×15×15 blocks, relative capture eye position/orientation, and at most 256 frozen entities. Capture excludes the local player. Entity appearance is restricted to visual NBT fields; detached preview entities are never added to the live client world. Render IDs are assigned separately, fixing the modern renderer's required-ID error discovered during native testing.
- Exterior camera fits occupied geometry, spins once per 8 seconds on hover, and interpolates to the interior with a 250 ms cubic-bezier(0.23,1,0.32,1) transition. Enter/exit can reverse continuously. Near-plane clipping prevents geometry behind the camera from covering the interior. Reduced motion disables orbit and interpolated transitions.
- Entity occlusion is simplified: a center ray checks opaque full blocks; partial blocks, glass, transparent geometry and intersecting entities can differ from full world rendering. Fluid models and block tint remain simplified. Entities are frozen snapshots, not simulated creatures.
- Legacy v1 snapshots and their service hashes remain compatible. Recapture creates v2; the first schema upgrade bypasses the normal per-target 30 minute upload cooldown once. Changed official previews still require candidate approval. The running local service must be restarted to receive v2.
- All four release builds passed, with 15 Java tests per target (60 total); all 8 Node tests passed. New checks cover v2 compressed round trips, entity/camera validation, interrupted/reduced camera motion, clipping, and another HTTP client downloading the shared entity snapshot.
- In an isolated 26.2 integrated test world, the actual capture routine returned 3375 blocks, retained sheep color and excluded the local player (`capture-verification.txt`). Native screenshots were reviewed for interior entry, exterior exit, and a scene containing a red sheep, armor stand and remote-player model. Search, Traditional Chinese and compact layout captures also completed. Synthetic scene screenshots verify rendering, not Catbud packet semantics or full two-Minecraft-instance sharing. Other targets passed compilation/tests; this update's entity/camera runtime check was on 26.2.

Evidence is in `versions/26.2/build/verification-client/screenshots/01-inside.png`, `01-exterior.png`, `02-search.png`, `03-zh-browser.png` and `04-compact.png`. Earlier real-server teleport acceptance limitations remain separate from this preview update.

## Shared-depth preview and manual camera correction (2026-10-11)

The user reported giant overlapping entities and lag in the actual daniel1689 preview, then approved an independent interior-view button. Single click now only selects; right-side left drag changes yaw/pitch immediately, wheel changes zoom, and the explicit interior/exterior button retains an interruptible 250 ms transition. Tile hover orbit and double-click teleport remain. Leaving a tile no longer changes the detail camera.

The previous per-entity GUI overlays and center-ray occlusion were insufficient for dense scenes. Blocks and frozen vanilla entity render states now use one native picture-in-picture color/depth target and camera per scene. Minecraft 26.x uses its native reversed-depth projection; 1.21.11 uses its conventional perspective matrix. Block meshes and entity states are prepared once per snapshot/resource generation. Static scene textures are reused; camera motion refreshes at most 30 times per second. Mesh/entity/texture caches are bounded, GPU targets are evicted after queued frame draws, and resources are released on closing/reloading. Detached block-attached entities receive a local synthetic anchor, never an uploaded absolute anchor. No schematic-mod runtime dependency was added.

A read-only copy of the user's saved daniel1689 snapshot contains 3375 blocks and 173 entities. It was loaded in an isolated 26.2 integrated-world client; reviewed images show entities behind glass at the correct scale, exterior selection, explicit interior entry, and changed dragged/zoomed views. With the pointer away, the latest scene redraw counter increased by one over 2.5 seconds (the final hover-angle update), with 4176 cached blits; this confirms static texture reuse, not a measured FPS guarantee in the user's full modpack. The v2 wire format is unchanged, so existing v2 previews work without recapture or restarting the service.

All four target builds passed 17 Java tests per target (68 total). This rendering-only patch does not change the local service; its preceding eight-test suite remains applicable. All four isolated native clients completed the revised camera/cache/search/Traditional Chinese/compact verification. Actual dense entity rendering and the live capture routine were exercised on 26.2; the other three runtime checks used synthetic block scenes without a loaded world. Previous live Catbud protocol and cloud acceptance gates remain open.

Latest visual evidence: `versions/26.2/build/verification-client/screenshots/02-stationary.png`, `03-explicit-interior.png`, `04-dragged-interior.png`, and `05-dragged-exterior.png`. The fresh dense run has no preview-extraction or block-attached-anchor errors. All four native reports are `versions/<version>/build/verification-client/verification-results.txt`; release archives exclude the verification client.

## 30-cube and preview double-click revision (2026-10-11)

The latest user instruction supersedes the independent mode button: only the right-side preview double-click toggles interior/exterior. A drag beyond three pixels clears the pending preview click; double-clicking a grid entry still teleports. Candidate/verified-report status text and the suggestions-received notice are removed from the interface without changing verification rules. Camera hints use separate short lines for narrow windows.

New captures use v3, exactly 30×30×30 with offsets -15..+14 on each axis (27,000 blocks). Capture readiness checks the enlarged horizontal corners, and camera/entity bounds are validated against snapshot dimensions on client and service. Existing v1 and v2 snapshots retain their format/hash compatibility. The existing per-warp upgrade exception permits the first v3 report without waiting for the regular upload interval; conflicting official preview contents still require administrator candidate approval. Restart the local Node service to accept v3; old files are not expanded or backfilled. The existing 128 KiB compressed / 1 MiB expanded limits and 256-entity cap remain.

Opening the browser requests server command suggestions unless one request is already pending, while shared-list fetches use a five-minute interval. Manual refresh has a ten-second interval. Opening does not recapture blocks.

All four target builds pass 18 Java tests each (72 total); all nine service tests pass, including another HTTP client downloading v3 and v2 compatibility. The isolated 26.2 native capture produced 27,000 blocks, retained sheep color and excluded the local player. Its native camera verification asserted that preview double-click changes mode; drag, zoom, static texture reuse, Traditional Chinese and compact screenshots also completed. It rendered both the user's older 15-cube dense fixture and synthetic 30-cube scenes. This does not replace remaining live-server acceptance gates.


## Local Workers/D1 integration and 50-cube capture (2026-10-11)

The user approved implementation and explicitly requested local work before Cloudflare login. No cloud account was accessed, database created remotely, or Worker deployed. All project versions remain 1.0.2.

Completion names now persist locally immediately and remain when later completion responses omit them. UUID/name merges retain visits and previews, avoid renamed duplicate entries, and preserve newer local arrival/failure evidence over older shared records. Inactive passes and unavailable warps keep their tiles and warnings while normal teleport paths are disabled. An explicit bottom-button retry is available only with a pass check from the last hour and the name in the latest suggestions; retry does not clear failure before confirmed arrival. Shared-list manual refresh now has a 30-second minimum. Preview loads have two concurrent slots, including stale-callback/session guards. Shared oversized previews return a terminal result while the local snapshot remains.

New v4 captures contain exactly 50×50×50 blocks at offsets -25 through +24. Production capture advances at most 4,096 blocks per tick and cancels for world/operation changes, leaving the cube, or unloaded chunks. The isolated 26.2 client captured all 125,000 blocks across 31 distinct client ticks and verified world-change cancellation. Its actual gzip was 13,748 bytes; the cloud canonicalizer accepted it and produced 13,351 bytes with six entities. This is one integrated test-world sample, not a dense-scene worst-case or free Worker CPU measurement. Existing v1/v2/v3 data remains readable.

The new cloud protocol has authorized contributor discovery, quota-bounded preflight tickets, binary uploads, private review candidates, authoritative hourly pass checks, paged public reads and published-preview downloads. Atomic D1 reservations include stored and reserved bytes; pending previews and live tickets share the 200-candidate bound. Admin approval requires a current active pass and is transactional with submission removal and audit. Local management supports grant/rotation, revoke, approve/reject, availability decisions, retained-preview rollback and unpublishing. Revocation releases incomplete tickets. Canonicalization normalizes palette indexes and shared entity appearance ordering, strips players and text entities, and applies the shared 64-entity limit. Nested visual-field privacy validation still needs completion before cloud posting can be enabled.

The persistent local runtime uses Miniflare with an actual D1 database under services/public-warps-cloud/.local-state/d1, binds only 127.0.0.1:8788 and applies both migrations. Local startup, repeated migration, concurrent management access, credential grant/revoke and public HTTP reads were exercised without login. It is separate from both the legacy JSON service and Wrangler's default local database location. The cloud deployment config retains POSTING_ENABLED=false. Local polling handles up to three due records per minute with a 2,600-check daily cap, sized for initial testing near 100 UUIDs rather than the 5,000-record storage maximum.

Verification: all four target builds passed 26 Java tests each (104 total); the legacy service passed 10 tests; local cloud D1 passed nine integration tests including approval rollback, quota concurrency, revocation, preview rollback, unpublish revisions and gzip bombs. Native 26.2 capture and existing camera/double-click/drag/zoom/Traditional Chinese/compact checks passed. Reviewed browser screenshots still use legacy dense and synthetic 30-cube fixtures and do not establish 50-cube rendering performance. Release JARs in out exclude the verification harness and were not installed into the user's game.

Remaining local work: delta/tombstone synchronization and Worker cache, extra shared metadata management, bounded backup/restore, nested entity privacy validation, storage/edge burst controls and full client-to-D1 end-to-end checks. Remaining external acceptance: live Catbud packet cases and free Worker CPU/upstream/D1 quota measurements. Setup and concrete local commands are in services/public-warps-cloud/README.md. The implementation goal remains open.


## Persistent local D1, backup recovery and shared metadata (2026-10-11)

The previous local startup/management checks did not prove persistence: Miniflare 5's v4 converter silently ignores d1Persist. Inspection found no database files under the documented state path. The local runtime now uses resourcePersistencePath, and a new integration test writes data, disposes the runtime, reopens the same directory, verifies persistence, takes an online SQLite backup while the runtime is alive, and restores to a separate directory. Public list/downloads, stored capacity counters and revoked contributor access survive recovery. Existing restore directories and malformed backups are rejected. The backup preserves the actual database filename for the stable local D1 binding and does not copy raw contributor credential files. These local backups are not claimed as a remote D1 export/restore implementation.

Administrator metadata updates now support bounded custom names, purpose descriptions and deduplicated tags. Updates and audit entries are atomic, published-list revisions change, and player identity/pass/availability cannot be assigned through this operation. The integration suite now passes 11 tests. No Java code changed in this increment; the prior four-target 26-test builds remain the applicable Java evidence. No Cloudflare login or deployment occurred. Incremental synchronization/cache, nested shared visual-data privacy, actual storage/edge controls and end-to-end/external acceptance remain open.


## Incremental synchronization and public cache (2026-10-11)

GET /v1/changes now provides revision-ordered, 100-entry pages with bounded history and tombstones. A migration initializes the retained-history floor; maintenance moves the floor transactionally when removing expired history. Stale history returns 410 and a changed paginated revision returns 409. Worker fetch now uses a 60-second Cache API response for public list/change reads and respects no-cache requests; writes/private review data are not cached.

The Java cloud client stores the shared revision and shared UUID membership separately from local discovery. Within the same hour it consumes deltas; each new hour uses a full list to refresh actual qualification timestamps without creating a change event for every unchanged pass check. History loss, paging conflicts and excessive delta pages discard partial results and fall back to a full read with cache bypass. Tombstones remove shared-only entries and downloaded previews, retain personally discovered/visited/captured entries and visits, and clear their shared metadata. Existing candidate/name records are treated as personal during cache loading. Session/source guards prevent removed downloads from reappearing through stale callbacks.

Local D1/Worker tests cover metadata deltas, unpublish tombstones, retained-history expiry, 101-change paging conflicts, Cache API reuse/conditional reads and explicit bypass. Java tests cover HTTP full-to-delta synchronization, expired-history fallback with no-cache, and personal-history preservation across tombstones. These contract tests are separate from the still-required full Java-client-to-D1 end-to-end flow. Shared entity nested visual-data privacy, capacity/edge controls and external CPU/upstream/live-server acceptance remain open. No cloud login or deployment was performed.


## Shared nested appearance policy (2026-10-11)

Inspection showed that root visual-field filtering alone retained private nested item/component data. A single shared JSON policy now drives the Java sender and Worker sanitizer. It allowlists nested equipment, item IDs/counts, dye/model/trim/glint visuals, block state properties, villager data, poses and display transforms. Unknown nested fields, names, lore, containers and owners are removed. Enchantments derive a boolean glint override without retaining their payload. The local snapshot is not overwritten. Shared players and text displays remain excluded.

The Worker uses a bounded SNBT reader with a 4,096-character input cap, 16-level nesting cap, 1,024-node cap, duplicate-key rejection and typed-array checks. Malformed tags fail with a generic client error; the Java sender omits invalid visual fields while preserving local data. The policy bounds scalar values and list lengths. Server re-canonicalization has stable ordering even after derived glint fields are added. These rules limit permitted visual data and do not claim to prove a client report describes an actual visit.

All four release builds passed 31 Java tests each (124 total), and 16 cloud-service tests passed. Equipment ID/dye/trim preservation, nested private-field removal, glint derivation, malformed/deep/duplicate data and canonical hash stability were exercised. The actual 26.2 125,000-block snapshot remains accepted by the Worker codec (13,360 shared gzip bytes, stable hash on a second canonicalization). An isolated 26.2 native run completed incremental capture, world-change cancellation, camera gestures/cache/search/language/compact checks and a new synthetic 50-cube rendering fixture. Reviewed native screenshots show the 50-cube scene; equipment details are too small to prove exact visual fidelity at that camera distance, so close-up equipment QA remains open. The dense legacy fixture remains compatible.

Remaining: Java client through real local D1 end-to-end flow, storage/edge budget controls and full local large-snapshot limits, close-up shared equipment QA, then externally authorized live-server and free Worker CPU/upstream/D1 acceptance. No login or deployment occurred; the full implementation goal remains open.

## Local client integration and large snapshot persistence (2026-10-11)

The opt-in `verifyCloudProtocol` test runs the production Java clients against a temporary actual D1 and the production application services. It covers discovery, private pending submission, administrator approval, a second client downloading the 125,000-block preview, shared equipment privacy, and a qualification change delivered through a delta. Catbud qualification is mocked; this is not live-server or deployed Worker acceptance. The fixture closes its HTTP clients, input stream, runtime and temporary database. Local Miniflare now fixes its root path explicitly so starting from a Minecraft version directory works.

Local snapshot limits are now 4 MiB compressed and 16 MiB expanded, independently of the unchanged shared 64 KiB / 1 MiB limits. A deterministic dense-scene test exceeds the former local 128 KiB limit and the shared limit, saves the complete local preview, reloads it, and checks every block and palette entry. Cloud queues contain sanitized shared previews; oversized shared previews produce arrival-only reports. Cache eviction now follows recent use and enforces 24 MiB of encoded previews alongside the 64-entry limit. Pending image data is bounded to 4 MiB while retaining arrival metadata. The durable cache remains bounded to 32 MiB and replaces the previous file atomically only after checking size.

Combined integration tests exposed intermittent NUL characters in the intermediate StringWriter JSON output on the installed Java 25.0.1 runtime. Snapshot JSON now writes directly through a UTF-8 writer, avoiding that intermediate string. The integration fixture checks encoded zero indices before round-trip decoding; the exact upstream cause is unconfirmed. Shared projection copies scene fields directly instead of serializing and reparsing the whole local scene. These changes also avoid large temporary JSON strings during snapshot and durable-cache saves.


Validation: all four normal release builds passed 33 PublicWarps tests each (132 total); the opt-in integration suites passed 34 tests each on 1.21.11 and 26.2. All 16 local cloud-service tests passed. Twelve version 1.0.2 release JARs were rebuilt. Close-up native equipment QA, decoded-preview memory bounds, storage/edge controls, real-server traces and actual free-cloud resource acceptance remain open. No Cloudflare login or deployment occurred.

## Preview memory ownership and shared equipment close-up (2026-10-11)

Count-only caches could retain 64 complete decoded snapshots and 64 arbitrarily large scene meshes. A shared weighted LRU now accounts for block indices, palette strings, entity appearance and mesh vertices. Decoded previews use a 48 MiB estimated budget. Meshes include their retained snapshot weight in a 192 MiB estimated budget. Mesh eviction removes its detached entity instances and closes matching GPU preview targets. Target cache keys include GUI scale and use a 96 MiB pixel-based estimate; target eviction runs after the frame completes so queued blits are not broken. Closing or reloading the preview screen clears the related caches.

These budgets are estimates, not measured heap/VRAM ceilings. A single oversized scene is retained to prevent repeated reconstruction every frame; extremely complex custom block models and many simultaneously visible dense scenes still require performance stress testing. The implementation preserves all captured geometry rather than truncating the 50-cube preview to meet the cache budget.

Three cache tests cover byte-pressure eviction following recent access, replacement and clear releasing resources, retaining one oversized scene for reuse, and snapshot string/entity weighting. All four builds passed 36 PublicWarps tests each (144 total), and the 1.0.2 releases were rebuilt. An isolated 26.2 native run completed all camera/cache/search/language/compact checks. Its static preview required zero redraws during a 2.5-second stationary interval. Incremental capture still produced 125,000 blocks over 31 client ticks and cancelled on world change.

The shared 50-cube fixture now positions a sanitized armor stand in the interior camera view. `projects/public_warps/versions/26.2/build/verification-client/screenshots/09-shared-equipment-closeup.png` was inspected: the red dyed leather helmet, armor stand and cyan diamond sword are visible at normal entity scale, with no overlapping foreground sheep. This proves those representative equipment visuals in 26.2; it does not prove every entity/model or other native game versions.

Remaining local work includes actual D1 storage/edge-budget controls and dense-scene performance acceptance. Live Catbud traces and free-cloud CPU/upstream/D1 acceptance remain external gates. No cloud login or deployment occurred.

## Physical storage thresholds and pre-D1 burst protection (2026-10-11)

StorageCapacity measures complete D1 database size through the documented result meta.size_after, alongside existing stored/reserved BLOB counters. Authenticated submissions stop at 350,000,000 bytes before consuming daily-write budget or creating new records. Approval of an existing candidate below 400,000,000 bytes may continue but does not add retained history after the soft line. At the hard line scheduled qualification stops while maintenance continues; qualifications are preserved, and published downloads remain available. New grants and metadata/approval operations also consult capacity. An already consumed upload ticket can be replayed without new writes even while capacity blocks growth. The local status command reports measured bytes and state; it was exercised against the persistent local database (147,456 measured bytes, zero stored/reserved preview bytes).

Database-size sampling is not an atomic physical-byte reservation and cannot prove in-flight operations never cross the lines. Existing atomic logical-byte reservations remain essential. Local deletion was observed to reduce reported physical size, so no fixed reclaim behavior is assumed for the remote database. Missing measurements fail closed for growth. Daily actual rows_read/rows_written monitoring and real cloud resource acceptance remain incomplete.

Worker fetch now calls Rate Limiting bindings before Cache API and D1. Local initial limits are 6,000 requests/minute per location, a broad 600 public requests/minute per source, and 120 POST requests/minute per source. Address keys are bounded and hashed; no raw source address or credential is logged. Binding outage/missing protection returns a generic 503; limits return 429 with Retry-After. These are location-scoped, eventually consistent burst controls, not global accounting or a promise to withstand unlimited attacks. Namespace identifiers require an account collision check before deployment.

22 service tests pass, including actual Miniflare rate-limit bindings, cache-hit limiting, no database access on rejected/outage requests, real D1 size growth/deletion, restricted-mode download preservation, unchanged pass status while background checks stop, soft-limit approval without history and hard-limit refusal without partial approval. The two Java-to-D1 suites pass on 1.21.11 and 26.2 (37 tests per target, including the cloud integration test). No Java release source changed in this increment; existing 1.0.2 releases remain applicable. Formatting checks pass. No Cloudflare login, deployment or formal account resource creation occurred.

## Local D1 cost telemetry (2026-10-12)

HTTP operations and scheduled qualification checks now own independent query counters through a measured D1 adapter. Local management commands also emit counters. Successful D1 result metadata supplies rows read/written and maximum observed database size; failed queries, missing metadata and incomplete batch results retain unknown counts. The telemetry does not log SQL, player identities, contributor credentials or source addresses, and adds no database writes for anonymous accounting. Cache-hit and burst-rejected requests bypass D1 and are not Worker invocation totals in these reports. Startup migrations and queries outside this adapter are excluded.

The bounded offline report tool accepts plain JSONL and Wrangler tail JSON events, deduplicates report IDs, groups by UTC day and marks partial measurements. Empty metering input is rejected rather than reported as zero usage. It flags observed daily totals at 2,500,000 rows read or 50,000 rows written. These flags do not yet drive a global automatic growth gate. Missing or sampled logs cannot establish account-wide billing, even when all received records have complete metadata; official account metrics and production log collection remain an external acceptance requirement.

The one-player local D1 discovery/qualification/preflight/upload/approval/list/preview fixture measured 55 queries, 118 rows read and 62 rows written. Public list and preview reads wrote no rows. Test output is saved under services/public-warps-cloud/build/local-d1-usage.jsonl. This is representative small-fixture evidence, not a production or 5,000-player capacity extrapolation. All 29 service tests passed, including complete-flow metering, independent operation counters, failure accounting and CLI import/deduplication/refusal cases. Java-to-D1 tests were forced to execute again on 1.21.11 and 26.2 and passed. No Java release source, version, installed game files or Cloudflare account resources changed in this increment.

## Previous-preview expiry during approval (2026-10-12)

Approval previously refreshed previous_until unconditionally, including discovery-only reviews, identical previews and soft-capacity approval that retained no new history. Repeated reviews could therefore prevent a historical preview from reaching its seven-day cleanup deadline. Approval now materializes one retention decision inside the same D1 update and changes both the historical hash and its deadline only when retaining a new previous preview. Existing deadlines remain intact otherwise; publication, submission removal and audit remain in the guarded atomic batch.

An actual local D1 regression test exercises discovery review, identical-preview review, soft-capacity replacement, expiry cleanup of old references and unreferenced BLOBs, then normal replacement with a fresh seven-day history deadline. All 30 service tests pass. The forced Java-to-D1 test runs on 1.21.11 and 26.2 pass. Formatting checks pass. The representative complete-flow cost is now 121 rows read and 62 rows written, including three additional reads for the materialized approval decision. No Cloudflare login or deployment occurred. Dense-scene performance, global daily-cost response and external acceptance remain open.

## Dense 50-cube mesh probes and fluid surface reduction (2026-10-12)

The isolated native 26.2 verifier now invokes the production mesh builder on solid, checkerboard, full-water and full-lava 50-cube fixtures, checks exact face counts and writes mesh-cost-verification.txt. A stepped-water fixture verifies the visible height edge remains. These are CPU mesh-construction probes, not full dense-scene GPU/interaction acceptance.

Across all four targets, repeated block states reuse model parts during one build with the existing deterministic seed. Fluid plane templates and direction order are reused. Faces between continuous same-fluid cells are omitted, while horizontal height edges remain; stacked fluid exterior walls use full height below another fluid cell. Full water/lava cubes now contain 15,000 outer faces instead of 750,000 faces. No snapshot block, capture range or entity data is removed. Fluid level rendering remains the existing approximation, not complete vanilla fluid meshing.

The native baseline recorded solid 76.6 ms, checkerboard 264.4 ms and full water 419.8 ms. Two optimized runs recorded full water 62.4/82.6 ms; checkerboard was 213.9/386.5 ms. Timings vary with warmup and machine load; they do not prove a universal speedup or smooth worst-case interaction. The 98 percent full-fluid face reduction is deterministic. Complex checkerboards still block the synchronous build, so spreading first-time mesh construction over frames and testing dense GPU workloads remain necessary.

All four targets build and package successfully at version 1.0.2. The latest native run checks camera gestures, cache behavior, Traditional Chinese/compact layout, entity equipment, 125,000-block incremental capture and fluid edge geometry. Release artifacts were generated in out, not installed into the user's game. No cloud resources changed.

## Incremental first-time preview traversal (2026-10-12)

All four preview renderers now advance palette parsing and block traversal through one per-screen build queue. The queue shares a cooperative two-millisecond budget across visible preview requests, keeps one active builder, bounds visible requests to 64 and chooses the last requested preview first, which gives the right-side detail preview priority when starting work. Requests for the same scene do not reset progress; invisible jobs and cleared/reloaded screens discard unfinished work. The complete mesh enters the existing weighted cache only after traversal finishes. Loading previews display a localized preparation message. Block processing advances in batches of 32 to avoid spending most of the budget checking the clock for empty cells.

Two new shared unit tests verify aggregate frame budgeting, single completion publication, detail priority, invisibility cancellation and clearing. All four targets passed 38 tests each (152 total) and were packaged as 1.0.2. Formatting/readability checks passed. The isolated 26.2 client completed gesture, layout, entity and capture checks; reviewed Traditional Chinese screenshots show all visible thumbnails ready. A separate native probe uses the production builder and queue across actual client ticks, verifies all 125,000 blocks produce 375,000 checkerboard faces, and records nine ticks with a 61.9 ms maximum slice. Static thumbnails recorded zero redraws over the stationary 2.5-second interval after preparation.

The two-millisecond limit is cooperative between work steps, not a strict maximum frame duration. A complex block model can overrun one step. Final face sorting, entity-state extraction and WarpSceneMesh grouping remain synchronous; the 61.9 ms probe includes final sorting and does not include full mesh grouping or dense GPU draws. Incremental finalization and complete dense-render/interaction stress acceptance remain open. No capture-range reduction, omitted entities, installed-game replacement or Cloudflare deployment occurred.

## Incremental sorting and material grouping (2026-10-12)

Dense preview finalization now uses a stable bottom-up merge that advances up to 256 copy/merge operations per step, then a WarpSceneMesh builder that advances up to 256 faces per step. The published mesh owns immutable face/group collections. Its estimated byte count is accumulated during grouping and returned in constant time, avoiding another full face scan during cache insertion. All four renderers use these stages within the existing cooperative per-screen budget. Entity creation/state extraction is still a separate synchronous preparation stage.

The sort regression checks empty/singleton, uneven lengths, duplicate keys and 375,000 entries against standard stable sorting. The grouping regression verifies opaque/translucent routing, order, byte accounting, rejection of unfinished publication and immutability after source-list changes. All four targets pass 40 tests each (160 total), formatting/readability checks pass and 1.0.2 release JARs are regenerated without installing them.

The latest isolated 26.2 native checkerboard probe retains 125,000 blocks and 375,000 faces across 67 real client ticks. Maximum observed queue slice is 2.3396 ms, including incremental sorting and grouping, compared with the preceding 61.9 ms probe that sorted synchronously. This is one measured CPU-building fixture, not a strict frame-time guarantee or dense GPU acceptance. The native interaction/layout/entity/capture checks complete, and the stationary interval has zero redraws. Reviewed shared-equipment screenshots retain the armor stand's red helmet and cyan sword at normal scale. Entity preparation, complex custom models, allocation/GC spikes, dense GPU rendering and multi-preview interaction still require further stress acceptance. No Cloudflare deployment or live-server completion claim is made.

## Incremental entity preparation (2026-10-12)

A named SceneBuild task now owns block traversal, stable sorting, entity preparation and material grouping on all four targets. Each entity-preparation step creates and extracts one frozen entity before yielding to the shared frame budget. The old snapshot-to-raw-entity cache is removed: published meshes already retain the extracted render states, and unfinished tasks can release their temporary state when canceled. Entity instances remain detached from the live world, preserve appearance/orientation and assigned IDs, and use the existing failure handling for unsupported types.

The native 26.2 dense probe now uses the actual production SceneBuild task rather than a separate face-only adapter. It includes 125,000 checkerboard blocks, 375,000 faces and 256 sheep/pig/zombified-piglin/armor-stand render states. All were retained across 70 client ticks, with a maximum observed queue slice of 2.2361 ms including sort, entity preparation and grouping. This validates preparation, not the GPU cost of drawing 256 entities or all dense geometry. The cooperative budget can still be exceeded by one unusually expensive model or allocation.

The first native run failed the stationary-redraw check because new previews were still becoming ready during the measurement window. The verifier now waits for the visible build queue to become idle, with a 30-second timeout, before starting that unchanged redraw assertion. The rerun completed native camera/layout/equipment/capture checks and reported zero redraws during the 2.5-second stationary interval. Four-target builds/tests and formatting/readability checks pass; release artifacts remain 1.0.2 and are not installed. Dense GPU drawing, multi-preview interaction and external acceptance remain open.

## Dense drawing and camera workload baseline (2026-10-12)

The isolated verifier now follows complete SceneBuild preparation with a real WarpScreen containing the same 50-cube checkerboard and 256 entity states. It exercises a stationary exterior, repeated mouse-drag rotation and double-click interior view, saves screenshots and writes dense-drawing-verification.txt. The probe samples Minecraft's last-frame elapsed-time field at client tick cadence and also records its reported FPS. Samples may repeat when multiple ticks occur per frame; they are not GPU timers or complete frame-interval distributions, and FPS reporting can lag phase transitions. Therefore the reported medians/percentiles are diagnostic samples, not a precise GPU performance claim.

Native 26.2 baseline: static median sample 5.69 ms / P95 565.79 ms / reported 7 FPS; drag median 36.92 ms / P95 39.25 ms / reported 3 FPS; interior median 3.35 ms / P95 3.82 ms / reported 28 FPS. The high static samples may include first drawing/warmup and do not prove a sustained static redraw fault; the separate prepared stationary-cache assertion still passes. Reviewed dense-drag screenshot shows the complete checkerboard cube in the grid and detail pane. Entity states are submitted, but this solid-heavy scene can visually occlude them and does not by itself prove appearance for all 256 models.

The dense interaction performance requirement remains unfulfilled. Code inspection identifies repeated full vertex submission and per-face normal-vector allocations on every changing-camera redraw as work to address. Retained geometry must preserve all captured blocks and entities; reducing capture range or silently dropping models is not an accepted fix. No release implementation, installed game files or cloud resources changed in this baseline increment.

## Cached face normals and retained-buffer API inspection (2026-10-12)

Every face now stores its normalized local direction when built. All four redraw paths use these components with the same vanilla pose-normal transformation instead of allocating two vectors and recalculating the cross product for every face on every changing-camera draw. The geometric winding, color, UV, light and overlay submission remain in the existing path. Mesh estimates add twelve bytes per face for the cached components. A regression verifies sloped winding/normalization and finite zero normals for degenerate faces. All four targets pass 41 tests each (164 total), and 1.0.2 releases are regenerated without installation.

Native 26.2 rerun retains 125,000 blocks, 375,000 faces and 256 entity states across 75 ticks with a 2.3052 ms maximum preparation slice. Dense drawing samples: static median 5.62 ms / P95 449.42 ms / reported 10 FPS; drag median 33.83 ms / P95 40.71 ms / reported 4 FPS; interior median 3.33 ms / P95 4.30 ms / reported 29 FPS. These tick-sampled values and lagging FPS do not establish a controlled universal speedup. Dense interaction is still inadequate. The normal-layout/equipment screenshots remain visually consistent; complete native checks pass.

Local bytecode/API inspection confirms that 26.2 exposes PreparedRenderType.drawFromBuffer for retained GPU vertex/index buffers, while 1.21.11 and 26.1.2 expose RenderType.draw(MeshData) and require an adapter. PictureInPictureRenderer sets color/depth overrides before calling scene rendering and dispatches collected entity features afterward. The vanilla entity vertex shader lights the supplied Normal directly rather than transforming it with ModelViewMat, and computes fog from supplied Position. Therefore a retained local-space vertex path must preserve camera-relative normals and fog explicitly; merely moving the camera matrix into uniforms would change appearance. Opaque and translucent draw ordering must remain consistent with entity depth. Persistent GPU vertex uploads, bounded ownership/release, incremental preparation and cross-version visual/performance acceptance are still pending. No cloud resources changed.

## Retained opaque geometry and incremental GPU uploads (2026-10-12)

The 26.2 renderer retains opaque block vertices in a per-mesh GPU buffer shared by grid and detail views. A dedicated vertex shader transforms local position and normal into camera space before vanilla lighting and fog calculations. Translucent faces and entity features retain the existing submission path and shared depth target. The geometry cache owns buffers, closes them on removal/reload/screen cleanup, and trims to 64 meshes or 128 MiB of actual vertex-buffer bytes at frame end, retaining one oversized mesh. This budget excludes the shared vanilla index buffer and the single unfinished upload; it is not a total GPU memory limit.

Initial vertex preparation now uploads at most 256 faces per task step through the same cooperative screen queue as mesh building. Tasks release partial GPU allocations when invisible or cleared; a shared regression verifies cancellation occurs exactly once. GPU allocation, driver calls and shared index allocation can still exceed a cooperative budget. A geometry-evicted CPU mesh is rebuilt through the queue when next requested; already submitted scenes can use the legacy path for that frame. No captured block or entity is removed to obtain the performance improvement.

All four targets pass 42 public-warp tests each (168 total) and the complete build produces 1.0.2 releases. Only 26.2 uses retained geometry at present. Inspection found 26.3 now uses RenderPearl and render-pass execution records; 1.21.11/26.1.2 require a different adapter. These ports, translucent retained geometry, native resource-lifetime stress, custom model performance and live Catbud protocol acceptance remain open. No Cloudflare login, deployment or installed-game replacement occurred.

The first incremental-upload native run passed UI checks but its dense timing started before the first preview request. The screenshot still displayed the loading message, so its dense measurements are invalid. The verifier now requires at least one published mesh and an idle queue before sampling. Corrected dense results are recorded separately after native completion; build success alone is not sufficient acceptance evidence.

Corrected native 26.2 run (15:58 local time) confirms the dense scene is visible in both panes after preparation. It retains all 125,000 blocks, 375,000 faces and 256 entity render states. Queue preparation including retained upload spans 164 actual client ticks; maximum observed slice is 2.3496 ms. Dense static samples: median 3.6021 ms / P95 4.4473 ms / reported 30 FPS; drag: median 5.4404 ms / P95 10.0906 ms / reported 22 FPS; interior: median 3.4598 ms / P95 3.9979 ms / reported 30 FPS. These tick-cadence samples are diagnostic, not GPU timers or a universal speedup. The test no longer times a loading screen. Native base UI/capture checks complete and the stationary-cache assertion passes. Prepared dense screenshots were inspected; the opaque checkerboard can occlude entities, so this does not prove visibility or appearance of every entity.

## RenderPearl retained geometry adapter (2026-10-12)

26.3 now uses the retained opaque-geometry builder and lifecycle path. Its adapter targets the cached picture-in-picture color/depth views through a version-specific accessor, creates a RenderPearl render pass, and supplies StagedVertexBuffer.ExecuteInfo to PreparedRenderType. The custom pipeline explicitly adopts vanilla entity-cutout color/depth state; compilation alone did not reveal the missing color attachment state, which the first native launch rejected and the adapter now fixes. Its vertex shader uses RenderPearl include syntax and explicit attribute/varying locations while preserving camera-relative normal, lighting and fog calculation. Other versions do not load this accessor.

Native verification also found 26.3's old platform helper never moved the test pointer or created a test world. It now sets the game's pointer coordinates and creates an isolated creative world, allowing static-cache and real 125,000-block capture checks. The synthetic-world native run passes shared depth, double-click/drag/zoom, locale/layout, capture cancellation and stationary reuse (one settling redraw within the allowed threshold). Reviewed house and dense exterior screenshots show both preview panes. The resource witness confirms one retained mesh shared by grid/detail, 54,000,000 actual vertex bytes, closed partial-allocation buffers after cancellation, and closed cached buffers after cleanup. This is a lifecycle witness for this scene, not complete long-session memory stress acceptance.

Measured 26.3 dense drawing: static median tick-sampled last-frame time 3.5519 ms / P95 4.1902 ms / reported 30 FPS; drag 7.0792 ms / P95 12.4255 ms / reported 16 FPS; interior 6.6311 ms / P95 7.8982 ms / reported 30 FPS. Preparation spans 260 ticks and maximum observed slice is 26.4627 ms. A build overlapped part of this native run, so these are diagnostic observations and do not establish isolated performance limits. They do not prove the requested performance gate or universal speedup. Four-target build/release passes, with 42 public-warp tests per target. Retained geometry is still absent from 1.21.11 and 26.1.2; translucent geometry, detailed slice attribution, custom models and live-server acceptance remain open.

The 26.2 rerun with the same resource witness also passes: one mesh, 54,000,000 vertex bytes, partial cancellation and cleanup close owned buffers. Static cache records zero redraws; dense static median sample 3.5431 ms / P95 4.6456 ms / reported 30 FPS, drag 5.3382 ms / P95 7.3475 ms / reported 27 FPS, interior 3.7401 ms / P95 5.2025 ms / reported 30 FPS. Preparation spans 158 ticks and maximum observed slice is 17.3099 ms. Differences across runs reinforce that the cooperative budget is not a hard latency guarantee; dense/custom-model and long-session performance acceptance remains open.

## Older rendering adapters (2026-10-12)

26.1.2 and 1.21.11 now have retained opaque-vertex builders and share their completed geometry between grid/detail panes. These versions expose RenderType.draw(MeshData) rather than PreparedRenderType, so their adapters retain the buffer directly and create a render pass with vanilla RenderSetup texture/lightmap/overlay bindings, dynamic transforms, scissor and the picture-in-picture color/depth overrides. Existing entity-feature and translucent-face dispatch still happens after opaque geometry. 1.21.11 explicitly preserves vanilla color/depth write and depth-test settings, uses its perspective matrix buffer, and fetches the lightmap through UV2/16; 26.1.2 uses the newer color/depth state and lightmap helper. Local API/shader inspection, rather than assumptions about compatibility, guided these differences.

The verification helpers for both older versions now create an isolated creative world and set mouse coordinates instead of immediately claiming world/pointer readiness. 26.1.2 native capture, static reuse, gestures, locale/layout, and partial/cached GPU resource cleanup pass. Both panes share one 54,000,000-byte dense vertex buffer. Reviewed equipment screenshot retains the red helmet and cyan sword. Dense tick-sampled last-frame diagnostics: static median 4.3369 ms / P95 6.176 ms / reported 30 FPS; drag 5.9881 ms / P95 9.7871 ms / reported 29 FPS; interior 3.7988 ms / P95 5.1211 ms / reported 30 FPS. Preparation spans 96 ticks with a 29.2669 ms maximum observed slice; a concurrent legacy compilation overlapped this native run, so measurements do not establish isolated latency limits. Cooperative budgeting remains distinct from a hard frame-time guarantee.

1.21.11 native checks also pass with a real isolated world, including 125,000-block capture/cancellation, static reuse (one settling redraw), preview gestures, locale/layout and shared-buffer/partial-cancel/cache-cleanup resource checks. Equipment screenshot preserves the red helmet and cyan sword at normal scale. Dense diagnostics: static median 5.326301 ms / P95 6.9109 ms / reported 30 FPS; drag 6.68 ms / P95 8.7823 ms / reported 30 FPS; interior 5.2891 ms / P95 6.9476 ms / reported 30 FPS. Preparation spans 94 ticks with a 21.893 ms maximum slice. These are tick-cadence diagnostic samples; driver/allocation/GC costs can still exceed the cooperative budget. Retained opaque geometry is now implemented on all four versions, without reducing the 50-cube capture range or the fixture's 256 entity states. The dense cube can occlude entities, and the equipment scene is not complete entity-type acceptance. Translucent retained geometry, slice attribution and broader model/long-session performance acceptance remain open; live-server packet and cloud deployment gates remain unmet.

## Coalesced vertex uploads and preparation diagnostics (2026-10-12)

All four retained-geometry adapters still encode at most 256 faces per advance, but now accumulate up to 2,048 faces before uploading a contiguous vertex range. The single-material 375,000-face fixture needs 184 writes instead of 1,465. A group owns one bounded staging allocation (approximately 288 KiB with the current vertex format); flush and cancellation release it. Publication checks that the entire vertex allocation has been filled, and each flush checks its contiguous offset. Native resource verification now cancels after encoding an unfinished batch and checks both its native staging pointer and owned GPU buffers are released. This budget does not cover driver allocations or shared index buffers.

Verification-only preparation profiling separates block traversal, stable sorting, entity extraction, grouping and GPU allocation/upload. Steps are classified by their starting state, so transitions can include the next stage. Wall times include driver/GC interruptions and do not establish their cause. Repeated 26.2 runs varied substantially: before coalescing one run had a 67.25 ms maximum upload step, whereas another had none over 2 ms; after coalescing one run still had a 9.887 ms maximum. This is not a controlled universal speedup claim. The 26.3 coalesced run still had a 24.8155 ms upload step. Older adapters recorded maximum upload steps of 0.5504 ms (26.1.2) and 0.8507 ms (1.21.11); 26.1.2 nevertheless had block/grouping interruptions up to 25.5962 ms. Cooperative scheduling is not a hard latency guarantee.

Gesture verification now derives the pointer position from the actual preview rectangle and checks yaw, pitch and zoom changes. 26.3 restores its maximized test window before resizing. These changes fix a false failure caused by the former fixed coordinates falling outside the preview, and avoid accepting ignored gestures. The latest older-version runs pass these assertions, dense drawing, capture, static reuse and staging/GPU cleanup. Driver/GC attribution, translucent retained geometry, broader model and long-session performance, live-server protocol evidence and Cloudflare acceptance remain open. No cloud login, deployment or installed-game replacement occurred.

The final isolated 26.2 rerun also passes the stronger drag/zoom assertions and staging cancellation checks. Its maximum upload step was 1.137 ms, but grouping still had a 13.7268 ms interruption and sorting 2.6936 ms. Dense static/drag/interior tick-sampled medians were 3.6688/5.4694/3.4758 ms with reported 30/29/30 FPS. These measurements reinforce the remaining first-load latency limitation rather than resolving it.


Four-target build and release packaging pass after coalescing. Each public-warp target passes 42 tests (168 total); formatting and readability checks pass. All four regenerated 1.0.2 release JARs contain the retained renderer and matching shader, and exclude WarpVerificationClient and MeasuredSceneBuild. Installed game files were not replaced.


## Segmented face storage (2026-10-12)

Block traversal, published face storage and atlas groups now append to fixed 256-reference segments instead of repeatedly growing a single face array. The small segment directory can still resize, and the merge sort retains its two full scratch arrays; this change removes bulk face-reference copying during traversal/grouping, not every allocation or GC pause. No faces, entities, capture range or ordering are removed. The dense regression compares all 375,000 sorted entries against standard stable sorting and verifies segment boundaries and invalid indices. Existing grouping tests continue checking completed immutable publication and source independence. Performance acceptance remains conditional on native evidence.


The isolated 26.2 native run after segmented storage passes UI gestures, capture, static reuse, dense drawing and staging/GPU cancellation/cleanup; reviewed dense exterior screenshot preserves both preview panes. It retains 125,000 blocks, 375,000 faces, 256 entity states and 54,000,000 GPU vertex bytes. Maximum grouping step was 0.1502 ms and sorting 0.1452 ms; block traversal still recorded an 11.8151 ms interruption. These single-run measurements cannot establish its cause or guarantee removal of first-load stalls. Dense static/drag/interior tick-sampled medians were 3.6889/5.7473/3.5635 ms with reported 30/29/30 FPS. The latest storage change has native coverage on 26.2 and shared regression coverage on all targets; other native adapters were not rerun for this change.


Final four-target build/release passes with 43 public-warp tests per target (172 total). Formatting/readability checks pass. Regenerated 1.0.2 JARs contain SegmentedFaceList and exclude native verification clients/profilers; no installed release was replaced.


## Existing live trace review and test instructions (2026-10-12)

Read the installed 26.2-Fabric_0.19.3 instance's existing public-warps-teleport.jsonl without modifying it. Command events span 2026-10-11 15:49:55–18:06:12 UTC+08; newer enabled events are not newer completed teleport tests. Grouped by operation, the file contains 56 commands, 24 arrival_counted events, 17 cancelled attempt closures and 2 counted dimension changes. Each counted operation has a position correction; no operation counts twice, counts after superseding/cancelled closure, or has an arrival target differing from its command. Twenty-three counted operations have both translated launch channels; the remaining rapid replacement has fresh matching boss_progress and qualified corrections, consistent with retained visible launch text. This is historical diagnostic evidence, not proof of current release behavior, independent actual arrival, correct snapshots or complete acceptance. No same-block counted arrival or explicit target_no_pass_or_set test is present; these gates remain unverified.

Command traces now record retainedLaunch and emit retained_launch_refreshed exactly when fresh matching Bossbar progress activates retained launch evidence. Qualification and arrival rules are unchanged. The regression checks that this activation reports true once, then false. This makes future rapid replacement logs explainable without treating missing translated text as automatic failure.

TESTING.md now consistently specifies v4, 50 cubed /125,000 blocks, offsets -25 through +24, compatibility with v1-v3 and shared-player exclusion. Its second-client procedure now checks server approval instead of relying on the removed UI report wording. Installed diagnostics and user configuration were not rewritten.


Enabled traces now include Fabric-provided Minecraft and public-warp mod version strings (unknown when unavailable), without credentials or account identifiers. The patch retains format 1 and adds fields; it cannot distinguish two different builds with the same declared 1.0.2 version, so matching test JAR hashes or user release selection still matter.


The sequential isolated 26.3, 26.1.2 and 1.21.11 runs now pass the segmented-storage UI/gesture/capture/static reuse and staging/GPU resource witnesses. All retain 125,000 blocks, 375,000 faces, 256 entity states and one 54,000,000-byte grid/detail buffer. Reviewed 26.3 exterior screenshot retains the expected layout. Maximum grouping steps are 0.2511/0.1374/0.1656 ms respectively; upload maxima are 1.1939/0.5364/0.6446 ms. This removes the prior missing native adapter coverage, not the broader performance gate. In particular, the latest 1.21.11 drag median tick-sampled last-frame time is 40.6191 ms (reported 32 FPS), substantially worse than preceding runs; timing and load variation are unresolved. Other dense drag medians are 5.9784 ms (26.3) and 5.8636 ms (26.1.2). No universal performance or long-session acceptance claim is made.


Final diagnostic build/release passes with 43 tests on each target (172 total), formatting and readability checks pass. Regenerated 1.0.2 release client classes contain the new trace fields/event and exclude native verification classes. Cloudflare remains undeployed and installed game files unchanged.


## Production cloud connection (2026-10-12)

At the user's request to proceed directly to cloud connection, authenticated Wrangler, created the dedicated catbud-public-warps D1 database (APAC), applied all three migrations and deployed https://catbud-public-warps.catbud-public-warps-cloud.workers.dev with the hourly qualification scheduler (three eligible records per minute), individual contributor credentials, review gating and existing free-tier protection settings. No paid service was enabled. Final deployment version is a3be58dc-8e69-4498-b4b0-5215a742c49a. D1, administrator secret and minute Cron bindings are live; generated rate-limit namespace IDs are dedicated to this configuration.

Added a restricted authenticated administrator endpoint and cloud CLI for status/list, contributor grant/revoke, qualified approval/rejection, availability, rollback/unpublish and metadata. Authentication uses a separate 32-byte secret with constant-time comparison; no arbitrary SQL endpoint exists. Credentials are saved under ignored .local-state/cloud and not printed. configure backs up the game's previous settings before switching its HTTPS endpoint/protocol/contributor credential. Local service and its database remain available independently.

Live verification found and fixed a Workers-only illegal invocation caused by calling native fetch through an object receiver. Qualification now invokes fetch with globalThis; a receiver-enforcing regression covers this difference. Network failures log a bounded message with UUIDs redacted and retain existing qualification. The real Chris_TW identity and active pass were checked by the deployed Worker, then its discovered name approved; the public list returns that record. No old 15/30-cube preview was enlarged or imported as a 50-cube preview.

Cloud acceptance checks return public list 200, anonymous submission 401, contributor attempting administration 403, malformed authenticated submission 400 and administrator capacity status 200. Remote D1 is healthy; upload credentials grant correctly. Machine-readable evidence is in ignored .local-state/cloud/acceptance.json. Tests pass: 33 service tests, 43 public-warp tests for each target (172 total), formatting/readability and four-target release build. The default new-install settings now point to the cloud; earlier settings are preserved unless explicitly configured.

The user's named 26.2-Fabric_0.19.3 instance actually uses Fabric Loader 0.19.5. With the game closed, backed up old public-warps/Core 1.0.1 JARs outside mods, installed matching 1.0.2 releases and verified SHA-256 equality. Existing game settings were backed up and configured with the owner's contributor credential. Other gameplay modules were not changed. A new real 50-cube capture must still be made in game; live preview upload/download and broader load/free-account usage are not claimed as fully accepted by these HTTP checks. No synthetic preview was published. The diagnostic Wrangler tail session has been stopped.


An additional real cloud preflight/upload accepted a 125,000-block v4 diagnostic snapshot with HTTP 202. Its unapproved hash remained unavailable to public download (404); the administrator rejected it immediately and it was never published. The consumed diagnostic ticket was removed and only that unpublished test record's preview cooldown reset, so testing does not delay the player's first real upload. This validates the remote upload/D1 path under the current account, not a real in-game capture or a sustained CPU budget guarantee. The ordinary preview rejection and background orphan cleanup rules remain unchanged.


## 2026-10-12 傳點首次失敗與自由預覽修正

首次傳送收到 target_no_pass_or_set 時從正式本機名單移除，只保留獨立名稱排除標記，避免補全、尚在進行的身分查詢及共用同步重新加入。舊快取中沒有有效傳送證據的失敗紀錄亦於讀取時移除。兩個傳送啟動訊號確認後即保留有效紀錄；後續失敗保留傳點與警告，按鈕及雙擊均允許重試，不再要求補全或有效月卡才能發出重試指令。成功到達次數仍只在實際到達後增加。

標題列新增未套用搜尋的傳點總數。右側左鍵旋轉、右鍵平移，外觀滾輪縮放範圍擴至 0.2–6，內部滾輪沿目前視線前後移動；雙擊維持視角切換。相機位置偏移共用於方塊與實體。

增加首次失敗／重開／共用同步不復活、曾有效後失敗仍保留與再次成功恢復，以及平移與內部移動回歸測試。四個 Minecraft 版本建置與測試通過；實服視角手感仍需玩家確認。

## 2026-10-12 已批准方案：兩服隔離與發布服務實作進度

使用者批准 SYSTEM_DESIGN_PROPOSAL.md 並提供管理登入郵箱；郵箱配置保存至被 Git 忽略的 .local-state/cloud/access-settings.json。尚未配置 Cloudflare Access 或部署本輪改動。

已新增 0004_server_namespaces.sql：舊資料歸入 catbud，catrealm 為獨立 namespace；傳點、候選、票券、changes／revision、稽核具備伺服器識別，投稿者增加角色及授權範圍。舊表暫保留供後續相容切換與復原；不能在舊版 maintenance 仍主導的正式環境直接套用這份遷移。後續必須統一儲存預留記帳與垃圾回收引用，再部署完整切換。

已新增 ServerScope、ServerQualification、ServerWarpList、ContributorPolicy、PublicationService、ServerSubmissions、ServerApplication。本地 v2 讀取、名稱候選與觀察回報已接入 Worker；可信有效觀察自動發布，一般來源待審核，首次失敗候選不公開。預覽上傳／審核網頁／管理操作／v1 相容切換／新 Java 同步與鏡頭尚待實作。現有正式服務與遊戲 JAR 保持不變。

唯一新增的重要 D1 集成場景 server-isolation.test.mjs 已通過：遷移／外鍵、同 UUID 兩服資格與快取、授權範圍、獨立公開清單及 revision、可信發布／冪等／交易失敗不公開、實際 v2 HTTP 401／403、可信 200 published 與一般 202 pending_review。新增端點後因新改動重跑這一項，未重跑舊四版本壓力驗收。這不是整體方案已完成。

## 2026-10-12 預覽投稿與管理網頁進度

新增 ServerPreviewUpload，接上 scoped preflight／upload／票券重試與預留空間釋放。size 為壓縮內容與服務端正規化內容的空間上限，客戶端可預留 65536 bytes；原內容 hash 仍須一致。首次預覽需已有有效紀錄或該來源待審的到達回報，不能靠名稱投稿直接發布。可信來源自動發布；一般來源仍需審核。相同 UUID／hash 的公共及管理預覽查詢均限制在指定伺服器。PublicationService 加入 metadata 驗證、上一版預覽七天／32 MiB 保留上限、審核交易衝突回應。

新增 AccessVerifier、ManagementQueries、ManagementActions、ManagementApplication 與獨立 management-worker。使用 jose 6 的標準 JWT 驗證、固定 Access team issuer／audience、到期／必要 claims／郵箱允許名單。寫入另檢查同源 Origin、綁定登入 token 的 HMAC CSRF token 與 JSON；回應 no-store／CSP。私有郵箱未寫入公開程式。管理端包括兩服候選與發布清單、候選詳情／私有預覽、批准／拒絕、用途／狀態／下架／上一版回退、投稿者角色／範圍／撤權／密鑰輪替與稽核。

新增 admin 管理網頁：待審核、已發布、投稿者、操作紀錄、服務狀態。Worker 在所有 assets 請求前驗證登入；Wrangler 設定 run_worker_first=true、preview_urls=false。預覽在 Web Worker 解碼有大小限制的快照並建立外露面，分類上色、旋轉／縮放／剖切、實體位置標記及受保護下載；未載入／失敗的預覽不能批准。網頁沒有永久管理憑證，所有投稿內容以文字節點呈現。

本輪必要驗證已通過：兩項重點測試（Access 偽簽章／錯 issuer／aud／過期／非允許郵箱／CSRF，以及擴充的 D1 隔離／候選預覽不外洩／發布後下載／票券冪等／metadata 不跨服／舊 revision 409／撤權）。另完成管理 Worker 一次 dry-run 打包及一次本機 mock 資料的瀏覽器檢查（預覽、按鈕載入狀態、兩服切換、窄螢幕）；私有截圖在 .local-state/admin-ui-desktop.png。測試對撤權狀態碼原先誤寫為 401，修正為服務既有的 403 後通過；未展開舊四版本驗收。

仍未完成：namespace maintenance／舊表與預留記帳切換、v1 相容及 scoped CLI、管理 requestId 稽核／重複操作處理與完整狀態統計；模組的兩服本機資料／同步／排隊狀態；連續鏡頭與鍵盤操控；正式 Access application 與環境設定、備份遷移／部署、私人投稿設定恢復、JAR 建置安装及一次真實發布／下載／登入驗收。管理网页目前只是本地可打包成果，不代表已能線上登入；本輪沒有更新正式 Worker、D1 或遊戲 JAR，整體 goal 保持 active。

## 2026-10-12 連續預覽鏡頭與鍵盤輸入

PreviewCameraControl 改為單一位置／朝向模型，外部／內部只判定拖曳方式，不再是渲染位置插值模式。初始距離取完整快照體積、以拍攝眼睛位置為目標；滾輪沿視線前後移動，進出範圍有一方塊滯回。外部拖曳繞目標旋轉，內部拖曳原地環視，固定 FOV；沿用原有 SceneCamera／方塊與實體共同矩陣及網格快取，沒有重建場景流程。

兩套 WarpScreen 都接上單擊預覽取得焦點、WASD／Space／Shift 規一化移動、R／右鍵重置、第一次 Esc 釋放控制及再次 Esc 關閉。預覽雙擊不再切換布林模式；傳點格子的雙擊传送保留。搜尋／列表／按鈕點擊、Tab、滑鼠離開預覽、視窗失焦、換傳點與關閉會釋放控制與按鍵。移動按時間積分，一幀最多50ms，位置／朝向平滑且可打斷。沒有選取資料或沒有預覽時不保持鍵盤控制。修改提示文字，移除右鍵平移／雙擊切換的舊提示。

重要驗證：26.2 的 PreviewCameraControlTest 三項通過，驗證連續進出範圍無瞬移、內部拖曳不改位置、斜向速度／不同幀率一致、失焦停止、reset 與共同相機投影。其他三版只編譯一次相容性；發現 1.21.11 的 outline 名稱和 26.3 不提供 GLFW 後，改用各版 GuiGraphics outline 與共通 InputConstants，重編譯通過。26.2 verificationClasses 編譯通過，更新舊驗證工具，使它使用滾輪進入空間而非雙擊模式。没有執行舊四版壓力／繪圖測試；實際控制手感與遊戲焦點仍留到本輪整合後一次原生驗收。

核對 Java 同步協議時發現 v2 changes 尚缺每筆 revision 與結果 etag，已補齊並以現有一項 D1 集成測試確認。未部署雲端、未更新已安裝 JAR。下一項是模組 serverId／耐久佇列／同步結果完整接入，再完成 maintenance 與正式切換；不要把現有 v1 客戶端直接用於貓域，避免接口域名與共用資料混用。剩餘工作沿用前段範圍，goal 未完成。

鏡頭最後補上窄而高的預覽案例：初始遠距不會在第一格滾輪被位置邊界截斷而跳躍，SceneCamera 的遠裁切面依鏡頭距離計算，保持遠景可見；26.2 現為四項重點測試通過，1.21.11 編譯通過。焦點釋放只清除預覽自己的拖曳狀態，不影響搜尋框文字選取拖曳。

## 2026-10-13 正式兩服切換、JAR 安裝及登入剩餘事項

本段更新前面尚未部署的進度。已完成兩服本機快取、固定 serverId 的資格及共用 API、持久投稿 ID／收據、私人投稿者角色與伺服器授權範圍、佇列恢復與待審／發布／拒絕原因顯示。既有貓芽快取只在相符帳號與伺服器下遷移，貓域不匯入貓芽資料。v1 路由只相容貓芽，v2 明確分服。

完成 scoped maintenance、空間預留與回收、管理 CLI／本機服務切換、管理 requestId 與交易內重複寫入保護、分服狀態與預算統計。0005 移除舊票券／傳點觸發器並統一預留記帳；0006 加入稽核 requestId。Cloudflare 帳號實際 Workers／D1 用量無法從本服務讀取，管理頁明確顯示未知。

正式切換前已將 D1 與舊 Worker 備份至忽略版本控制的 `.local-state/cloud/cutover`，暫停寫入後套用 0004–0006，再部署新版。公共 Worker 部署版本為 `4f66b9ec-4e83-41d4-a306-e188f252d14f`；既有兩筆貓芽資料保留，私人 owner 授權為兩服 trusted。復原時必須先暫停寫入並保存新版資料，不能直接讓舊 Worker 寫入已切換的資料庫。

正式公共接口驗證：兩服清單各自讀取、v1 只讀貓芽、匿名寫入 401。使用已存在的 Chris_TW 真實到達紀錄與 50³ 快照完成可信發布、預覽上傳及下載；下載解壓後的正規化內容 hash 一致，同 hash 從貓域讀取為 404。沒有為此發布合成傳點。其他本機傳點會在遊戲連線後按佇列與資格規則上傳，不能將本機列表數量當作已發布雲端數量。

必要驗證已完成：一項擴充 D1 隔離／交易／回收／相容路由測試、26.2 三組同步與儲存重點測試（15 項）、一次 Java→本機 D1 協議集成、一次 26.2 原生預覽輸入驗證，以及最後四版 assemble。原生短驗證確認拖曳、滾輪、WASD／Space／Shift、R 與搜尋／Esc 焦點；沒有重跑四版壓力測試。原生證據為 `versions/26.2/build/verification-client/preview-input-verification.txt`，正式發布證據為私有 `real-submission.json`。

四版 1.0.2 JAR 已產出至根目錄 `out`。26.2 JAR 已安裝到使用者指定遊戲實例，備份舊 JAR／設定／warps 資料後恢復正式雲端與私人 owner 設定；安裝檔與產物 SHA-256 相同（`1EF43C1C4E46BDD39AF77E9D192FEEFE6AA3ED192B5B387A59409A46199B1349`）。

管理網頁已部署，但登入尚未完成。允許郵箱已設為使用者提供的地址；目前 Wrangler 憑證建立 Access application 兩次均收到 Cloudflare 403（第二次為使用者要求重試）。缺少 Access team domain／audience 時，網頁、靜態檔案及管理 API 均回覆 503／no-store，不會公開管理內容。下一步只需要補上 Access 權限與真實登入驗收，操作說明見 `services/public-warps-cloud/ACCESS_SETUP.md`。整體 goal 尚未完成。

使用者自行建立 Access 應用後，已配置 `https://xirojiang.cloudflareaccess.com` 與使用者提供的 AUD，管理 Worker 的五項登入設定更新成功。未登入訪問管理網站回覆 302 至相同 team 的登入頁；登入頁回覆 200，包含相符 AUD 與郵件登入介面。此證據確認應用與 AUD 對應，但尚未證明管理者真實登入及登入後管理操作；下一步請管理者開啟管理網站，以允許郵箱完成 One-time PIN 登入。

使用者隨後回覆「看到了」，確認允許郵箱的實際登入與管理介面可見。登入阻礙已解除；T6／T12 尚餘一次登入後寫入確認，可在已發布傳點中不修改欄位直接按「儲存用途資訊」，確認成功提示與操作紀錄。此檢查不必變更傳點用途，也不需要再建置或重跑既有測試。

## 2026-10-13 本次批准方案交付完成

管理者儲存用途資訊後回覆「有」，確認正式登入後寫入成功提示。T6／T12 的正式管理讀寫驗收完成。收尾核對既有真實發布證據、原生輸入 PASS、Java→D1 協議測試零失敗、四版 JAR 及已安裝 26.2 hash；未重跑測試。

| 要求 | 完成證據 |
| --- | --- |
| R1 可信自動發布 | Chris_TW 真實到達及 50³ 預覽正式發布、下載內容一致 |
| R2 公開下載／受限投稿 | 正式匿名写入 401；角色矩陣、撤權及公開下載集成 |
| R3 網頁管理 | Access／JWT／CSRF 與管理交易集成；使用者正式登入及儲存確認 |
| R4 兩服隔離 | 各服固定資格接口；D1／Java scoped 集成；正式貓域不能讀貓芽預覽 |
| R5 首次紀錄規則 | 首敗排除、有效後保留與重試的儲存及客戶端驗證 |
| R6 連續鏡頭 | 共享相機測試與 26.2 原生拖曳／滾輪／鍵盤／焦點 PASS |
| R7 同步狀態 | 持久投稿 ID／收據、待審與已發布區分、拒絕原因及分服計數 |
| R8 免費及有限驗證 | 免費 Worker／D1、每日預算與硬限制；重要集成及一次原生輸入驗證 |
| R9 遷移與 JAR | 正式及遊戲備份、0004–0006 遷移、原資料保留、四版 1.0.2 JAR；指定實例安裝 hash 相同 |

T1–T8 對應 D1 隔離／角色／交易／維護、Access 集成及正式管理讀寫；T9 原生短驗證；T10 固定 server scope 與舊回應保護的客戶端集成；T11 四版建置；T12 正式真實投稿／下載、管理讀寫與原資料保留。玩家操作、管理流程與復原說明已記於 README 與本紀錄。

保留批准方案的限制：網頁預覽為分類上色簡化模型；大量傳點可能因每日資格預算延後查核；來源回報不能證明快照真實性；不承諾所有場景固定 FPS 或大規模負載容量。本次實作完成不擴大為上述額外驗收。

## 2026-10-13 可信補全名稱直接發布

使用者批准改以私人可信來源的補全名稱加上該服 UUID／月卡資格作為名稱發布門檻。ServerSubmissions 會檢查月卡，復用既有自己的 discovery 候選並自動發布；PublicationService 的交易仍查核可信角色、伺服器範圍、最新資格及 revision。一般來源的名稱發現不自動發布。未傳送的發布不寫 first_valid_at／last_success，預覽上傳仍要求到達證據。

可信來源首次失敗時撤下只有補全證據的名稱、保存失敗標記，補全重送不能重新加入；曾有效的傳點後續失敗仍保留。一般來源失敗不能直接撤下可信公開內容。

唯一新增的重要 D1 場景 trusted-discovery.test.mjs 通過：一般來源待審、無月卡不發布、可信有月卡立即發布、不偽造到達／跨服資料、首次失敗撤下且重送不復活、有效後失敗保留。初次執行修正了測試讀取 Response 的方式後通過，沒有重跑全套測試或四版建置。

公共 Worker 已部署版本 `43621f55-e9fe-4b2a-a91d-ea794d0ef97a`。讀取既有 owner 可信角色／貓芽授權／未過期 discovery 候選，重新提交 16 個原有名稱，全部通過正式資格檢查並發布；不以人工 SQL 直接改 published。無需更新 JAR，其他玩家在名單刷新後可下載。之前的名稱待審項會變成已發布；一般來源及預覽審核規則保留。

## 2026-10-13 傳送恢復狀態被預覽重試延後

使用者回報管理頁標記無法使用後，實際重新傳送能恢復本機狀態，網站仍未恢復。核對正式 D1 的 daniel1689 仍為 unavailable，而遊戲快取已有較新的成功到達、待送 valid_launch 與預覽；確認不是只有網頁快取問題。

原因為 PublicWarpsClient 的狀態／名稱回報與預覽共用 nextRetry，預覽冷卻或退避會延後獨立狀態回報。分開 nextMetadataRetry／metadataFailures 與預覽重試時間；成功到達也立即加入獨立 successful_arrival 回報，不依賴方塊擷取完成或預覽上傳。資格與憑證失敗仍按原規則退避，不繞過雲端資格或審核。

只執行一次擴充 Java→本機 D1 集成：管理標記 unavailable 後，可信 Java 客戶端回報成功到達，另一個讀取客戶端看到 available；原有月卡失效及预覽保留仍通過。四版只建置兼容產物。已用遊戲原有持久 valid_launch 回報及相同 submissionId 補送正式接口，回覆 200 published，D1 availability=available、revision=6；未人工直接改狀態。

使用者關閉遊戲後，備份並安裝修正版 26.2 1.0.2 JAR；安裝與 out 產物 hash 相同（65343C7F7BFA8F639D39E53FD6EA37A08E6E4DFDE17C1337D5A6F899AB50B41C）。本輪無需更新正式 Worker。已開啟的管理頁仍需按重新整理重新讀取，不聲稱頁面有即時推送。

## 2026-10-13 管理清單與客戶端資訊、懸停預覽

管理頁改為桌面左右獨立捲動，選取列表底部傳點時右側詳情回到頂部；窄螢幕選取後跳至詳情。加入服務端參數化搜尋、每頁 25 筆、上一頁／下一頁及搜尋總數，不只過濾已載入的頁面。分類、伺服器範圍、Access 保護與既有視覺風格保留。管理 Worker 部署版本 c1d86352-10b7-4399-8474-2b55c0fe4e74。

兩套 WarpScreen 的格子及常用列保留玩家 ID，自訂名稱另列；右側獨立換行顯示自訂名稱、用途與標籤摘要。客戶端搜尋加入用途內容，仍以真實玩家 ID 發出傳送指令。右側手動相機控制保留。

PreviewMotion 新增懸停循環：外觀 2.5 秒、內部 2.5 秒，各旋轉 360 度，重用現有 250ms ease-out 切換；離開回外觀，換格子／捲出畫面時結束前一格循環，重新懸停從外觀開始。reducedMotion 停用自動環繞與視角循環。不為动画重新擷取或建立網格。

重要驗證：擴充一項 D1 集成覆蓋用途搜尋、兩服搜尋隔離、26 筆分頁及其他分類查詢；PreviewSceneTest 五項通過，包含循環、離開重置及 reducedMotion；本機瀏覽器使用 36 筆資料確認底部選取、獨立捲動、分頁、搜尋、预覽與窄螢幕無橫向溢出，並檢視截圖。四版建置通過。原生啟動第一次漏掉 verifyWarps 參數，已中止普通遊戲啟動；修正為 verifyWarps＋verifyWarpsInput 後短驗證 37 秒通過並檢視名稱／用途／標籤截圖。沒有執行壓力或 FPS 驗收。其後只將自訂名稱移入既有換行流程並移除重複 ID 行，重建產物，不重跑測試。

備份舊 JAR 後已安裝最終 26.2 1.0.2，與 out 產物 SHA-256 相同（581479CC9340B981B784DC95E4A540FBAC048AB17BAE41FC79333EE1A29CE97F）。其他版本產物同時更新。遊戲資料及私人設定未更動；重新開遊戲與重新整理網頁即可使用。

管理頁後續依使用者畫面縮減頂部高度：伺服器選擇／刷新移至標題同一列，減少頁面 padding、分類列及空白訊息區留白，內容仍填满剩餘視窗並各自捲動。在同一 1280×900 本機截圖中，內容頂部由約 281px 提前至 151px，可顯示更多資訊。沿用一次既有瀏覽器排版檢查（搜尋、分頁、下方選取、窄螢幕），通過並檢視新截圖；未建置或改動 JAR。管理頁已部署版本 67f28d48-2828-4ca9-8fef-6365afeaf081。

## 2026-10-13 遊戲內編輯自己的傳點

已按批准方案加入兩套原生編輯視窗，四版維持 1.0.2。僅目前正版帳號 UUID、所在伺服器、既有共用傳點與目前補全匹配時顯示入口；ID 固定，名稱與多行說明分開。取消保留原資料，錯誤保留草稿，衝突提供最新內容與保留草稿選擇。

後端新增 owner/challenge、owner/verify、owner/warp、owner/metadata 與 0007_owner_editing.sql，正式 D1 已套用新增表。短效挑戰一次使用，編輯憑證綁定 UUID／伺服器；名稱 64、說明 512 字，只更新這兩欄並保留標籤、狀態、預覽。儲存強制刷新該服月卡資格，使用 revision／requestId 避免覆蓋與重放。官方登入 Token 僅由模組傳給固定 Minecraft 官方端點，不傳给 Worker。

重要驗證已完成：一組 D1 所有權／隔離／重放／過期／欄位白名單／衝突與資格失效集成、既有兩服集成、Java 協定檢查、四版建置與一次原生編輯介面。原生介面報告明確不包含官方身份成功驗證。26.2 JAR 已備份並安裝，SHA256 與 out 相同：9FEC9638EE02711FF3A5B279AF2B95E85CDC3AB7A4115DE68B6CD0CBC1845C38。

備份紀錄：首次相對路徑 D1 匯出失敗，但新增表遷移仍執行；已改用絕對路徑完整匯出目前資料至私人 owner-edit-backup/current-owner-edit.sql。新增表遷移沒有修改既有傳點資料。不能把後補備份稱為遷移前備份。

正式驗收發現 Workers 不接受 redirect:error；改為 manual 後，官方服務發現及固定 hasJoined 端點均回覆 403。正式 verify 仍回覆 503，拒絕儲存；本機官方請求成功不能作為正式驗證成功證據。尚未完成真正正版帳號的成功儲存，目標保持未完成。OWNER_CERTIFICATE_PROPOSAL.md 記錄建議改用官方 V2 憑證與一次性挑戰簽名，等待使用者批准後才替換驗證協定。

目前公共 Worker e4824124-c07f-4fe9-b8e9-8a7bc89aa354；管理 Worker 71fb864f-637b-4f3e-adba-e6747a952ca2，支援多行說明及玩家編輯稽核顯示。正式通用下載／傳送及既有投稿權限不依賴 owner 驗證。

## 2026-10-13 官方憑證簽名驗證（已批准）

使用者批准 OWNER_CERTIFICATE_PROPOSAL.md 後，移除 join／hasJoined 驗證模組。模組向固定官方 player/certificates 接口取得 V2 公鑰憑證，以私鑰對服務來源、伺服器、UUID、challengeId 與隨機雜湊簽名。Worker 驗證官方簽發者的 UUID／到期／公鑰簽名（官方 SHA1withRSA 協定），再驗證 SHA256withRSA 挑戰簽名。官方公鑰由本機固定官方 publickeys HTTPS 接口取得，兩份 4096 位元 RSA 公鑰編入部署；不從玩家輸入或環境欄位接受替代信任來源。短效 owner 權限有效期為五分鐘與憑證剩餘期限中的較短者。

登入 Token 只送往官方，HTTP 不跟隨重新導向；私鑰只在記憶體中解析與簽名，傳送給 Worker 的資料只有公鑰、官方簽名、到期時間及挑戰簽名。離開表單、切換帳號／伺服器後，不繼續提交後續步驟。

必要驗證：一組 D1 集成使用真實生成的 RSA 簽名，覆蓋合法更新、錯 UUID、假簽發者、篡改到期、過期、錯挑戰、重放、跨服與原有更新邊界；Java 兩項測試涵蓋官方 PEM 格式解析／SHA256withRSA 簽名與投稿不包含私鑰或 Token。四版 1.0.2 建置成功。正式 Worker 假簽發者測試回覆 403、重放回覆 401；不能將這兩項當成真正正版帳號的成功證據。

公共 Worker 已部署 d04b74a2-08fb-4338-9d79-19da9a75e615；管理 Worker不需再次部署。使用者再次關閉遊戲後，備份並替換 26.2 JAR，安裝與 out 的 SHA256 同為 C41AF7DF0A55A7EC303E22BE4BC8EBCE77470C2442836150C629A9304B6A39CF。已請使用者登入正版帳號完成一次正式儲存，等待結果，尚未宣稱整體驗收完成。

## 2026-10-13 無月卡玩家的獨立本機驗收

使用者表示 WhiteMeowGX 沒有月卡，只能使用貓域測試服；原編輯入口要求目前帳號 UUID、遊戲玩家 UUID、補全及共用名單一致，因此未通過正式資格的本地傳點沒有入口。使用者批准建立獨立本機環境，不調整正式月卡規則或入口安全條件。

新增 tools/owner-verification.mjs，僅綁定 127.0.0.1:8789，使用 .local-state/owner-verification/d1/ 獨立資料庫與現行 WarpApplication／OfficialCertificateVerifier。只提供指定帳號在指定伺服器的測試傳點、模擬資格回應及編輯／名單路由；沒有投稿或管理入口，不連接正式 D1。UUID 25f38894-76d8-43f0-9dba-4ef94663ae84 由官方 WhiteMeowGX 公開資料查詢取得；此查詢只用於建立測試資料，不是所有權證明。真正編輯仍需官方帳號憑證、UUID 匹配及一次性挑戰簽名。

使用者關閉遊戲後，完整備份 public-warps-service.json 與 warps 資料夾；暫時切換 localhost cloud 協定，移除測試設定中的正式投稿者憑證，清除快取的名單版本／etag／sharedIds 以強制重取本機名單，保留其他內容。備份與原位置記錄在私人 .local-state/owner-verification/game-setup.json，正式設定及快取可在遊戲關閉後以備份恢復。此輪不需更新 JAR。

只確認本機名單與挑戰 200、無憑證驗證 401；正版編輯成功仍待使用者測試。test-status 與成功後的私人 result.json 可讀取 owner_metadata 稽核及實際修改，沒有密碼、登入 Token、私鑰或原始請求記錄。正式雲端的有月卡玩家成功儲存仍需另外驗收。

## 2026-10-13 真正正版帳號本機儲存驗收完成

首次測試未顯示編輯按鈕的原因是驗收工具直接 INSERT published=1，沒有經過現有 UPDATE 發布觸發器產生名單 revision／changes；原客戶端已持有 revision=0，增量同步讀到空更新，因此 sharedIds 沒有本人 UUID。修正驗收工具為先建立未發布資料，再以既有發布觸發器更新；確認 changes?since=0 回覆 revision=2 及本人傳點。沒有放寬正式介面 UUID／補全／共享名單條件，也沒有要求再次重開遊戲。

使用者重新整理後確認按鈕出現及儲存成功。本機 test-status 與私人 result.json 均確認 officialAccountSaved=true、本人 owner_metadata 稽核與自訂名稱「測試自定名稱」、revision=3。此接口沿用未覆寫信任公鑰的正式 OfficialCertificateVerifier，真正官方 V2 憑證與挑戰簽名已通過；只有測試資料與資格回應是模擬。

使用者再次關閉遊戲後，從備份逐檔恢复完整服務設定與測試前快取，核對檔案雜湊一致；正式服務 URL 恢復為 catbud-public-warps.catbud-public-warps-cloud.workers.dev，正式投稿者設定亦由原始檔復原，未輸出私人憑證。已停止本機驗收服務，保留結果及新版 JAR。正式雲端的有月卡玩家成功儲存仍未取得證據，不能把本機月卡模擬當成正式驗收；整體目標尚不能宣稱全部完成。

## 2026-10-13 網域縮短至 cbwp.workers.dev

使用者在 Cloudflare 將帳號子域改為 cbwp，公共與管理 Worker 分別改名 api／control。同步 wrangler.jsonc／wrangler.admin.jsonc 的名稱，沿用原 D1 ID、角色與資料，不重新建立資料庫。公共新來源為 https://api.cbwp.workers.dev，管理入口為 https://control.cbwp.workers.dev；遊戲預設網址與目前 26.2 serviceUrl、README 和 Access 設定文件均同步，歷史實作紀錄與備份保留原地址。

更新 control 的 ADMIN_ORIGIN 及私人來源設定；使用者已將既有 Access 應用 hostname 更新為 control.cbwp.workers.dev。匿名 / 與 /api/session 均確認 302 跳轉到原有 xirojiang.cloudflareaccess.com，API 的 catbud／catrealm 名單 200，分別 25／0 筆。這證明新域名資料讀取與登入入口保護，未將它描述為新域名的已登入編輯驗收。

只建置四版 1.0.2，未重跑無關測試。使用者關閉遊戲後，備份 JAR 與完整連線設定，再替換 26.2 JAR／serviceUrl，保留其他設定、私人投稿者憑證與快取。安裝 hash 與 out 相同：B4895BA580C7B07E32D252042454F05D2941A67A184C08DC2B2B94773D2D990E。舊域名不再作為目前連線來源。
