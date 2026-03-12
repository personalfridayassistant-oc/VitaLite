# VitaLite Structure, Coding Practices, and Zombie Pirate Strategy Notes

## VitaLite structure overview

- **`base-api`**: client/runtime plumbing, lifecycle, event infrastructure, service integrations, low-level helpers.
- **`api`**: script-facing gameplay APIs (queries, movement, bank/inventory/equipment widgets, pathfinding, wrappers).
- **`plugins`**: feature and automation plugins; typically each plugin has a config interface and plugin class.
- **Root app (`src/main`)**: launcher/bootstrap and application-level wiring.

## Plugin coding practices observed in VitaLite

1. **Plugin metadata and discovery**
   - Plugins use `@PluginDescriptor` with clear name/description/tags.
2. **Dependency injection**
   - Plugin services/config are injected with `@Inject`, and configs use `@Provides` + `ConfigManager`.
3. **Automation loop style**
   - Automation plugins commonly extend `VitaPlugin` and implement a stateful `loop()` method.
4. **Query-first entity selection**
   - Interactions are built around query objects (`NpcQuery`, `TileItemQuery`, `TileObjectQuery`, `PlayerQuery`) with filters and nearest sorting.
5. **Widget/API abstraction usage**
   - Bank, inventory, equipment, and GE logic goes through APIs (`BankAPI`, `InventoryAPI`, `EquipmentAPI`, `GrandExchangeAPI`) rather than direct packets.
6. **Tick-aware pacing**
   - Actions are spaced with tick-based waits (`Delays.tick(...)`) to avoid spam and desync.

## Zombie pirates (max-efficiency) process notes

Source page highlights (money making guide):

- Method is in **Wilderness** at **Chaos Temple**, and assumes high KPH but meaningful PK risk.
- Suggested setup centers around:
  - Ranged-focused gear (commonly Venator setup + Salve amulet (ei)).
  - Cannon usage with strong positioning.
  - Blighted food/restores, looting bag, herb sack, and frequent banking.
- Escape guidance from the page:
  - If attacked, prioritize escape routes south/bridge, or stepping-stone route where applicable.
  - Ferox route can be trap-prone in teams.

Source page highlights (player killing page, general):

- PKers commonly use Tele Block, freezes, and burst damage/special attacks.
- Multi-combat zones increase danger due to team pile potential.
- Practical anti-PK survival basics include fast detection, immediate movement/escape, and preserving teleport options.

## Automation design translated from the process

The plugin should follow this high-level state machine:

1. **PREPARE_BANK**
   - Open bank, deposit excess, equip required gear, withdraw consumables and utility items.
   - Detect missing setup and branch to GE restock.
2. **RESTOCK_GE**
   - Travel to GE, place basic buy offers for missing essentials, collect, return to bank prep.
3. **TRAVEL_TO_CHAOS_TEMPLE**
   - Prefer configured teleport chain (burning amulet / spell / fallback walk).
4. **COMBAT_AND_LOOT**
   - Maintain prayers/sustain thresholds, attack nearest zombie pirate, take configured loot.
5. **ESCAPE_PKER**
   - Trigger on hostile player proximity / combat target check.
   - Prioritize immediate emergency teleport; if unavailable, path toward configured safe fallback tile.
6. **RESET_TRIP**
   - On low supplies/full inventory, leave and repeat bank cycle.

## Safety and operational caveats

- The implementation is intentionally conservative and config-driven because player behavior, world state, and anti-PK scenarios vary.
- GE automation is best-effort and may require tuning prices and item names.
- Wilderness survival decisions are time-critical; emergency actions are prioritized over optimization.
