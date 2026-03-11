package com.tonic.plugins.doomassistant;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("doomassistant")
public interface DoomAssistantConfig extends Config
{
    @ConfigItem(
            keyName = "enabled",
            name = "Enable assistant",
            description = "Master toggle for Doom of Mokhaiotl assistant",
            position = 0
    )
    default boolean enabled()
    {
        return false;
    }

    @ConfigItem(
            keyName = "autoPray",
            name = "Auto swap protection prayers",
            description = "Automatically swap overhead prayer based on detected projectile style",
            position = 1
    )
    default boolean autoPray()
    {
        return true;
    }

    @ConfigItem(
            keyName = "delveLevel",
            name = "Delve level",
            description = "Current delve level used for timing table bucket logic",
            position = 2
    )
    default int delveLevel()
    {
        return 6;
    }

    @ConfigItem(
            keyName = "magicProjectileIds",
            name = "Magic projectile IDs",
            description = "Comma separated projectile IDs for Doom magic attacks",
            position = 3
    )
    default String magicProjectileIds()
    {
        return "";
    }

    @ConfigItem(
            keyName = "rangedProjectileIds",
            name = "Ranged projectile IDs",
            description = "Comma separated projectile IDs for Doom ranged attacks",
            position = 4
    )
    default String rangedProjectileIds()
    {
        return "";
    }

    @ConfigItem(
            keyName = "meleeProjectileIds",
            name = "Melee projectile IDs",
            description = "Comma separated projectile IDs for Doom melee attacks",
            position = 5
    )
    default String meleeProjectileIds()
    {
        return "";
    }

    @ConfigItem(
            keyName = "rockProjectileIds",
            name = "Falling rock projectile IDs",
            description = "Comma separated projectile IDs for falling rock markers",
            position = 6
    )
    default String rockProjectileIds()
    {
        return "";
    }

    @ConfigItem(
            keyName = "dashProjectileIds",
            name = "Dash/Car projectile IDs",
            description = "Comma separated projectile IDs for dash/car lane markers",
            position = 7
    )
    default String dashProjectileIds()
    {
        return "";
    }

    @ConfigItem(
            keyName = "slamProjectileIds",
            name = "Slam projectile IDs",
            description = "Comma separated projectile IDs for slam/shockwave impact markers",
            position = 8
    )
    default String slamProjectileIds()
    {
        return "";
    }

    @ConfigItem(
            keyName = "showMechanicOverlays",
            name = "Show mechanic overlays",
            description = "Render per-mechanic overlays for rocks, dash lanes, slams, and hazards",
            position = 9
    )
    default boolean showMechanicOverlays()
    {
        return true;
    }

    @ConfigItem(
            keyName = "mechanicOverlayTicks",
            name = "Mechanic overlay duration (ticks)",
            description = "How long mechanic tile overlays remain visible after detection",
            position = 10
    )
    default int mechanicOverlayTicks()
    {
        return 6;
    }

    @ConfigItem(
            keyName = "escapeHpPercent",
            name = "Fail-safe HP %",
            description = "Trigger emergency escape when HP % drops to or below this threshold",
            position = 11
    )
    default int escapeHpPercent()
    {
        return 40;
    }

    @ConfigItem(
            keyName = "escapePrayerLevel",
            name = "Fail-safe prayer",
            description = "Trigger emergency escape when prayer points drop to or below this value",
            position = 12
    )
    default int escapePrayerLevel()
    {
        return 12;
    }

    @ConfigItem(
            keyName = "teleportItemName",
            name = "Escape item name",
            description = "Inventory item to use for emergency escape",
            position = 13
    )
    default String teleportItemName()
    {
        return "Royal seed pod";
    }

    @ConfigItem(
            keyName = "teleportAction",
            name = "Escape item action",
            description = "Action used on emergency escape item",
            position = 14
    )
    default String teleportAction()
    {
        return "Commune";
    }

    @ConfigItem(
            keyName = "lobbyTargetName",
            name = "Lobby target name",
            description = "NPC/Object near arena exit to route back to lobby when fail-safe triggers",
            position = 15
    )
    default String lobbyTargetName()
    {
        return "Lift platform";
    }

    @ConfigItem(
            keyName = "lobbyAction",
            name = "Lobby action",
            description = "Action on the lobby target",
            position = 16
    )
    default String lobbyAction()
    {
        return "Use";
    }

    @ConfigItem(
            keyName = "announceWarnings",
            name = "Announce warnings",
            description = "Send game chat warnings for mechanics/fail-safe",
            position = 17
    )
    default boolean announceWarnings()
    {
        return true;
    }
}
