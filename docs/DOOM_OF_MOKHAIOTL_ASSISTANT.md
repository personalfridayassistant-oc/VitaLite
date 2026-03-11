# Doom of Mokhaiotl Assistant (Implemented Plugin)

This repository includes a Doom assistant plugin implementation at:

- `plugins/src/main/java/com/tonic/plugins/doomassistant/DoomAssistantPlugin.java`
- `plugins/src/main/java/com/tonic/plugins/doomassistant/DoomAssistantConfig.java`
- `plugins/src/main/java/com/tonic/plugins/doomassistant/DoomAssistantOverlay.java`
- `plugins/src/main/java/com/tonic/plugins/doomassistant/DoomAssistantPanel.java`
- `plugins/src/main/java/com/tonic/plugins/doomassistant/DoomTimingProfile.java`

## Implemented mechanics and overlays

The plugin now supports dedicated mechanic overlays for Doom mechanics described in the strategy page, including:

- **Falling rocks** (rock throw markers)
- **Dash / car lanes**
- **Slam / shockwave markers**
- **Generic hazard fallback markers** for any additional tracked mechanics

These markers are rendered as tile overlays with unique colors/labels and expire after a configurable number of ticks.

## What is implemented from the guidance

### 1) Detector methods using client hooks
The plugin uses runtime hooks/state to detect and react to Doom mechanics:

- `ProjectileMoved` event classification (`magic/ranged/melee`) via configurable projectile ID sets.
- Mechanic marker detection from projectile impact positions for rocks/dash/slam/hazards.
- Distance checks against Doom NPC to warn about adjacency/tongue melee risk.
- Live state tracking (`lastDetectedStyle`, `currentCallout`, `currentPhase`) for overlay and panel.

### 2) Delve-specific timing tables
A dedicated timing profile class (`DoomTimingProfile`) encodes separate heuristic cadence tables:

- Delve **1–4**: slower hazard cadence.
- Delve **5–6**: medium cadence.
- Delve **7–8+**: fast cadence with tighter warning lead.

The active profile is selected by configured delve level every tick.

### 3) Fail-safe exits + lobby routing behavior
The plugin includes emergency logic that triggers when HP/prayer are under configured thresholds:

1. Tries emergency teleport item/action from inventory.
2. If teleport unavailable, interacts with configured lobby object target.
3. If object route unavailable, tries configured lobby NPC route.
4. Emits warning if no route is found.

### 4) Full assistant shell (panel + overlay + alerts)
The implementation includes:

- In-client navigation panel with live encounter telemetry.
- Scene overlay with phase/style/tick/HP/prayer/callout data.
- Per-mechanic tile overlays for rocks/dash/slam/hazards.
- Chat alert callouts for shockwave/rock windows and fail-safe events.

## Configuration notes
Projectile IDs are intentionally configurable because encounter IDs can differ by revision and should be verified in-client.

Recommended setup flow:
1. Enable plugin.
2. Enter Doom encounter and log projectile IDs from client debug tools.
3. Fill magic/ranged/melee plus rock/dash/slam projectile ID config fields.
4. Enable mechanic overlays and tune marker duration ticks.
5. Set fail-safe HP/prayer and emergency item/action values.
6. Validate overlay callouts over several kills and tune delve level.

## Strategy references used
Encounter strategy baseline:
- https://oldschool.runescape.wiki/w/Doom_of_Mokhaiotl/Strategies

Local VitaLite capability references:
- `docs/SCRIPT-DSL.md`
- `docs/SDK-DOCS.md`
