package com.tonic.plugins.weapongear;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("weapongearautoequip")
public interface WeaponGearAutoEquipConfig extends Config
{
    @ConfigItem(keyName = "enabled", name = "Enable auto equip", description = "Master toggle for weapon-triggered gear + prayer switching", position = 0)
    default boolean enabled() { return false; }

    @ConfigItem(keyName = "switchDelayTicks", name = "Switch delay (ticks)", description = "Ticks to wait after a weapon-triggered switch", position = 1)
    default int switchDelayTicks() { return 1; }

    @ConfigItem(keyName = "triggerWeapon1", name = "Selection 1 - Trigger weapon", description = "Weapon item id (e.g. 1215) or name (e.g. Dragon dagger)", position = 10)
    default String triggerWeapon1() { return ""; }
    @ConfigItem(keyName = "equipItems1", name = "Selection 1 - Equip items", description = "Comma-separated item ids or names to equip", position = 11)
    default String equipItems1() { return ""; }
    @ConfigItem(keyName = "prayers1", name = "Selection 1 - Prayers", description = "Comma-separated prayers to enable (e.g. PIETY, PROTECT_FROM_MAGIC)", position = 12)
    default String prayers1() { return ""; }

    @ConfigItem(keyName = "triggerWeapon2", name = "Selection 2 - Trigger weapon", description = "Weapon item id or name", position = 20)
    default String triggerWeapon2() { return ""; }
    @ConfigItem(keyName = "equipItems2", name = "Selection 2 - Equip items", description = "Comma-separated item ids or names to equip", position = 21)
    default String equipItems2() { return ""; }
    @ConfigItem(keyName = "prayers2", name = "Selection 2 - Prayers", description = "Comma-separated prayers to enable", position = 22)
    default String prayers2() { return ""; }

    @ConfigItem(keyName = "triggerWeapon3", name = "Selection 3 - Trigger weapon", description = "Weapon item id or name", position = 30)
    default String triggerWeapon3() { return ""; }
    @ConfigItem(keyName = "equipItems3", name = "Selection 3 - Equip items", description = "Comma-separated item ids or names to equip", position = 31)
    default String equipItems3() { return ""; }
    @ConfigItem(keyName = "prayers3", name = "Selection 3 - Prayers", description = "Comma-separated prayers to enable", position = 32)
    default String prayers3() { return ""; }

    @ConfigItem(keyName = "triggerWeapon4", name = "Selection 4 - Trigger weapon", description = "Weapon item id or name", position = 40)
    default String triggerWeapon4() { return ""; }
    @ConfigItem(keyName = "equipItems4", name = "Selection 4 - Equip items", description = "Comma-separated item ids or names to equip", position = 41)
    default String equipItems4() { return ""; }
    @ConfigItem(keyName = "prayers4", name = "Selection 4 - Prayers", description = "Comma-separated prayers to enable", position = 42)
    default String prayers4() { return ""; }

    @ConfigItem(keyName = "triggerWeapon5", name = "Selection 5 - Trigger weapon", description = "Weapon item id or name", position = 50)
    default String triggerWeapon5() { return ""; }
    @ConfigItem(keyName = "equipItems5", name = "Selection 5 - Equip items", description = "Comma-separated item ids or names to equip", position = 51)
    default String equipItems5() { return ""; }
    @ConfigItem(keyName = "prayers5", name = "Selection 5 - Prayers", description = "Comma-separated prayers to enable", position = 52)
    default String prayers5() { return ""; }

    @ConfigItem(keyName = "triggerWeapon6", name = "Selection 6 - Trigger weapon", description = "Weapon item id or name", position = 60)
    default String triggerWeapon6() { return ""; }
    @ConfigItem(keyName = "equipItems6", name = "Selection 6 - Equip items", description = "Comma-separated item ids or names to equip", position = 61)
    default String equipItems6() { return ""; }
    @ConfigItem(keyName = "prayers6", name = "Selection 6 - Prayers", description = "Comma-separated prayers to enable", position = 62)
    default String prayers6() { return ""; }

    @ConfigItem(keyName = "triggerWeapon7", name = "Selection 7 - Trigger weapon", description = "Weapon item id or name", position = 70)
    default String triggerWeapon7() { return ""; }
    @ConfigItem(keyName = "equipItems7", name = "Selection 7 - Equip items", description = "Comma-separated item ids or names to equip", position = 71)
    default String equipItems7() { return ""; }
    @ConfigItem(keyName = "prayers7", name = "Selection 7 - Prayers", description = "Comma-separated prayers to enable", position = 72)
    default String prayers7() { return ""; }

    @ConfigItem(keyName = "triggerWeapon8", name = "Selection 8 - Trigger weapon", description = "Weapon item id or name", position = 80)
    default String triggerWeapon8() { return ""; }
    @ConfigItem(keyName = "equipItems8", name = "Selection 8 - Equip items", description = "Comma-separated item ids or names to equip", position = 81)
    default String equipItems8() { return ""; }
    @ConfigItem(keyName = "prayers8", name = "Selection 8 - Prayers", description = "Comma-separated prayers to enable", position = 82)
    default String prayers8() { return ""; }

    @ConfigItem(keyName = "triggerWeapon9", name = "Selection 9 - Trigger weapon", description = "Weapon item id or name", position = 90)
    default String triggerWeapon9() { return ""; }
    @ConfigItem(keyName = "equipItems9", name = "Selection 9 - Equip items", description = "Comma-separated item ids or names to equip", position = 91)
    default String equipItems9() { return ""; }
    @ConfigItem(keyName = "prayers9", name = "Selection 9 - Prayers", description = "Comma-separated prayers to enable", position = 92)
    default String prayers9() { return ""; }

    @ConfigItem(keyName = "triggerWeapon10", name = "Selection 10 - Trigger weapon", description = "Weapon item id or name", position = 100)
    default String triggerWeapon10() { return ""; }
    @ConfigItem(keyName = "equipItems10", name = "Selection 10 - Equip items", description = "Comma-separated item ids or names to equip", position = 101)
    default String equipItems10() { return ""; }
    @ConfigItem(keyName = "prayers10", name = "Selection 10 - Prayers", description = "Comma-separated prayers to enable", position = 102)
    default String prayers10() { return ""; }
}
