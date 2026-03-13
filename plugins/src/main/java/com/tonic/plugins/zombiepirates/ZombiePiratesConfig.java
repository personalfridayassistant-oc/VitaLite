package com.tonic.plugins.zombiepirates;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("zombiePirates")
public interface ZombiePiratesConfig extends Config
{
    @ConfigSection(
            name = "Setup",
            description = "Gear/inventory setup and copy helpers",
            position = 0
    )
    String setupSection = "setupSection";

    @ConfigItem(keyName = "enabled", name = "Enable automation", description = "Master automation toggle", position = 0)
    default boolean enabled() { return false; }

    @ConfigItem(keyName = "gearList", name = "Gear list", description = "Comma-separated gear names to equip", position = 1, section = setupSection)
    default String gearList() { return "Salve amulet(ei), Void ranger helm, Elite void top, Elite void robe, Void knight gloves, Venator bow"; }

    @ConfigItem(
            keyName = "copyGearHint",
            name = "Copy worn gear",
            description = "Button type is unavailable in this client API version; copy gear CSV manually for now",
            position = 2,
            section = setupSection
    )
    default String copyGearHint() { return "Manual copy only"; }

    @ConfigItem(keyName = "inventoryList", name = "Inventory list", description = "CSV in Name:Amount format used to prepare inventory", position = 3, section = setupSection)
    default String inventoryList()
    {
        return "Blighted manta ray:8, Blighted super restore:4, Burning amulet:1, Royal seed pod:1";
    }

    @ConfigItem(
            keyName = "copyInventoryHint",
            name = "Copy inventory",
            description = "Button type is unavailable in this client API version; copy inventory CSV manually for now",
            position = 4,
            section = setupSection
    )
    default String copyInventoryHint() { return "Manual copy only"; }

    @ConfigItem(keyName = "foodName", name = "Food item", description = "Primary food item name", position = 5)
    default String foodName() { return "Blighted manta ray"; }

    @ConfigItem(keyName = "foodCount", name = "Food count", description = "Amount to withdraw per trip", position = 6)
    default int foodCount() { return 8; }

    @ConfigItem(keyName = "restoreName", name = "Restore potion", description = "Prayer/restore potion base name", position = 7)
    default String restoreName() { return "Blighted super restore"; }

    @ConfigItem(keyName = "restoreCount", name = "Restore count", description = "Amount to withdraw per trip", position = 8)
    default int restoreCount() { return 4; }

    @ConfigItem(keyName = "lootList", name = "Loot list", description = "Comma-separated loot names to take", position = 9)
    default String lootList() { return "Zombie pirate key, Coins, Rune, Dragon, Blighted, Adamant seeds, Gold ore"; }

    @ConfigItem(keyName = "teleportName", name = "Travel teleport", description = "Item used to travel to Chaos Temple area", position = 10)
    default String teleportName() { return "Burning amulet"; }

    @ConfigItem(keyName = "teleportAction", name = "Travel teleport action", description = "Action used on travel teleport", position = 11)
    default String teleportAction() { return "Chaos Temple"; }

    @ConfigItem(keyName = "escapeTeleportName", name = "Emergency teleport", description = "Item used to emergency teleport", position = 12)
    default String escapeTeleportName() { return "Royal seed pod"; }

    @ConfigItem(keyName = "escapeTeleportAction", name = "Emergency action", description = "Action for emergency teleport", position = 13)
    default String escapeTeleportAction() { return "Commune"; }

    @ConfigItem(keyName = "rebankOnSlots", name = "Rebank free slots <=", description = "Leave when free inventory slots fall to this threshold", position = 14)
    default int rebankOnSlots() { return 1; }

    @ConfigItem(keyName = "eatAtHpPercent", name = "Eat at HP %", description = "Eat when HP is at or below this percent", position = 15)
    default int eatAtHpPercent() { return 58; }

    @ConfigItem(keyName = "escapeAtHpPercent", name = "Escape at HP %", description = "Emergency escape threshold", position = 16)
    default int escapeAtHpPercent() { return 35; }

    @ConfigItem(keyName = "pkerRadius", name = "PKer detection radius", description = "Nearby player distance to trigger escape logic", position = 17)
    default int pkerRadius() { return 10; }

    @ConfigItem(keyName = "chaosTempleX", name = "Chaos Temple X", description = "Target X for travel fallback", position = 18)
    default int chaosTempleX() { return 3245; }

    @ConfigItem(keyName = "chaosTempleY", name = "Chaos Temple Y", description = "Target Y for travel fallback", position = 19)
    default int chaosTempleY() { return 3600; }

    @ConfigItem(keyName = "safeTileX", name = "Escape tile X", description = "Fallback run tile X", position = 20)
    default int safeTileX() { return 3090; }

    @ConfigItem(keyName = "safeTileY", name = "Escape tile Y", description = "Fallback run tile Y", position = 21)
    default int safeTileY() { return 3520; }

    @ConfigItem(keyName = "minimizedOverlay", name = "Minimize chatbox overlay", description = "If enabled, overlay shows only one compact row", position = 22)
    default boolean minimizedOverlay() { return false; }

    @ConfigItem(keyName = "restockMappings", name = "Restock mappings", description = "CSV mappings name:id (e.g. Blighted manta ray:24589)", position = 23)
    default String restockMappings() { return "Blighted manta ray:24589, Blighted super restore:24595, Burning amulet:21166, Royal seed pod:19564"; }
}
