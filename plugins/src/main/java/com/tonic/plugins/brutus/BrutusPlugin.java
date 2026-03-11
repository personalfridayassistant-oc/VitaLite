package com.tonic.plugins.brutus;

import com.google.inject.Provides;
import com.tonic.Logger;
import com.tonic.api.entities.NpcAPI;
import com.tonic.api.entities.TileItemAPI;
import com.tonic.api.entities.TileObjectAPI;
import com.tonic.api.game.SkillAPI;
import com.tonic.api.threaded.Delays;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.data.wrappers.ActorEx;
import com.tonic.data.wrappers.ItemEx;
import com.tonic.data.wrappers.NpcEx;
import com.tonic.data.wrappers.TileItemEx;
import com.tonic.data.wrappers.TileObjectEx;
import com.tonic.queries.NpcQuery;
import com.tonic.queries.TileItemQuery;
import com.tonic.queries.TileObjectQuery;
import com.tonic.util.VitaPlugin;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.util.Arrays;

@PluginDescriptor(
        name = "# Brutus Script",
        description = "Automates Brutus encounter loop with loot, sustain, and emergency teleport",
        tags = {"boss", "combat", "automation", "brutus"}
)
public class BrutusPlugin extends VitaPlugin
{
    private static final String[] DEFAULT_RESTART_FALLBACK_ACTIONS = new String[]{"Enter", "Pass", "Start", "Fight"};

    @Inject
    private BrutusConfig config;

    @Inject
    private Client client;

    @Provides
    BrutusConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(BrutusConfig.class);
    }

    @Override
    public void loop() throws Exception
    {
        if (!config.enabled())
        {
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            return;
        }

        if (shouldEmergencyTeleport())
        {
            if (useEmergencyTeleport())
            {
                Delays.tick(2);
                return;
            }

            Logger.warn("[Brutus] Emergency state reached but no teleport action succeeded.");
            return;
        }

        boolean inFightArea = isInFightArea();

        if (maybeEatFood())
        {
            Delays.tick(1);
            return;
        }

        if (maybeDrinkPotion())
        {
            Delays.tick(1);
            return;
        }

        if (inFightArea && pickupConfiguredLoot())
        {
            Delays.tick(1);
            return;
        }

        if (inCombatWithBrutus())
        {
            Delays.tick(Math.max(1, config.tickDelay()));
            return;
        }

        if (!inFightArea)
        {
            return;
        }

        NpcEx brutus = new NpcQuery()
                .withNameContains(config.bossName())
                .alive()
                .sortNearest()
                .first();

        if (brutus != null)
        {
            NpcAPI.interact(brutus, config.bossAttackAction(), "Attack");
            Delays.tick(Math.max(1, config.tickDelay()));
            return;
        }

        restartFight();
        Delays.tick(Math.max(1, config.tickDelay()));
    }

    private boolean inCombatWithBrutus()
    {
        ActorEx<?> interacting = com.tonic.data.wrappers.PlayerEx.getLocal().getInteracting();
        if (interacting == null)
        {
            return false;
        }

        String targetName = interacting.getName();
        return targetName != null && targetName.toLowerCase().contains(config.bossName().toLowerCase());
    }

    private boolean pickupConfiguredLoot()
    {
        String[] lootNames = splitCsv(config.lootList());
        if (lootNames.length == 0)
        {
            return false;
        }

        if (InventoryAPI.isFull() && !maybeEatFood())
        {
            return false;
        }

        TileItemEx loot = new TileItemQuery()
                .withNames(lootNames)
                .within(Math.max(1, config.lootRadius()))
                .sortNearest()
                .first();

        if (loot == null)
        {
            return false;
        }

        TileItemAPI.interact(loot, "Take");
        return true;
    }

    private boolean maybeEatFood()
    {
        int maxHp = SkillAPI.getLevel(Skill.HITPOINTS);
        if (maxHp <= 0)
        {
            return false;
        }

        int currentHp = SkillAPI.getBoostedLevel(Skill.HITPOINTS);
        int currentHpPercent = (int) Math.round((currentHp * 100.0) / maxHp);
        if (currentHpPercent > clampPercent(config.eatAtHpPercent()))
        {
            return false;
        }

        ItemEx food = findFirstInventoryItem(splitCsv(config.foodList()));
        if (food == null)
        {
            return false;
        }

        InventoryAPI.interact(food, "Eat", "Drink", "Guzzle");
        return true;
    }

    private boolean maybeDrinkPotion()
    {
        int currentPrayer = SkillAPI.getBoostedLevel(Skill.PRAYER);
        if (currentPrayer > Math.max(0, config.drinkPrayerAt()))
        {
            return false;
        }

        ItemEx potion = findFirstInventoryItem(splitCsv(config.potionList()));
        if (potion == null)
        {
            return false;
        }

        InventoryAPI.interact(potion, "Drink");
        return true;
    }

    private boolean shouldEmergencyTeleport()
    {
        int maxHp = SkillAPI.getLevel(Skill.HITPOINTS);
        int currentHp = SkillAPI.getBoostedLevel(Skill.HITPOINTS);
        int hpPercent = maxHp <= 0 ? 100 : (int) Math.round((currentHp * 100.0) / maxHp);

        boolean weak = hpPercent <= clampPercent(config.weakHpPercent());
        boolean outOfFood = findFirstInventoryItem(splitCsv(config.foodList())) == null;

        if (outOfFood && config.noFoodTeleportOnlyInFightArea() && !isInFightArea())
        {
            outOfFood = false;
        }

        return weak || outOfFood;
    }

    private boolean useEmergencyTeleport()
    {
        String teleportItem = config.teleportItem();
        if (teleportItem == null || teleportItem.trim().isEmpty())
        {
            return false;
        }

        ItemEx item = InventoryAPI.getItem(teleportItem.trim());
        if (item == null)
        {
            return false;
        }

        String action = config.teleportAction() == null ? "" : config.teleportAction().trim();
        if (action.isEmpty())
        {
            InventoryAPI.interact(item, "Teleport", "Break", "Rub");
            return true;
        }

        InventoryAPI.interact(item, action, "Teleport", "Break", "Rub", "Commune");
        return true;
    }

    private void restartFight()
    {
        String targetName = config.restartTargetName();
        if (targetName == null || targetName.trim().isEmpty())
        {
            return;
        }

        String action = config.restartAction();
        String safeAction = (action == null || action.trim().isEmpty()) ? "Fight" : action.trim();

        NpcEx restartNpc = new NpcQuery()
                .withNameContains(targetName.trim())
                .sortNearest()
                .first();

        if (restartNpc != null)
        {
            NpcAPI.interact(restartNpc, safeAction, "Talk-to", DEFAULT_RESTART_FALLBACK_ACTIONS[0], DEFAULT_RESTART_FALLBACK_ACTIONS[1], DEFAULT_RESTART_FALLBACK_ACTIONS[2], DEFAULT_RESTART_FALLBACK_ACTIONS[3]);
            return;
        }

        TileObjectEx restartObject = new TileObjectQuery()
                .withNameContains(targetName.trim())
                .sortNearest()
                .first();

        if (restartObject != null)
        {
            TileObjectAPI.interact(restartObject, safeAction, DEFAULT_RESTART_FALLBACK_ACTIONS[0], DEFAULT_RESTART_FALLBACK_ACTIONS[1], DEFAULT_RESTART_FALLBACK_ACTIONS[2], DEFAULT_RESTART_FALLBACK_ACTIONS[3]);
        }
    }

    private static ItemEx findFirstInventoryItem(String[] itemNames)
    {
        if (itemNames.length == 0)
        {
            return null;
        }

        for (ItemEx item : InventoryAPI.search().collect())
        {
            if (item.getName() == null)
            {
                continue;
            }

            String itemName = item.getName().toLowerCase();
            for (String configured : itemNames)
            {
                if (itemName.contains(configured.toLowerCase()))
                {
                    return item;
                }
            }
        }

        return null;
    }

    private boolean isInFightArea()
    {
        int radius = Math.max(0, config.fightAreaRadius());
        if (radius == 0)
        {
            return true;
        }

        String bossName = config.bossName();
        if (bossName == null || bossName.trim().isEmpty())
        {
            return true;
        }

        NpcEx nearestBrutus = new NpcQuery()
                .withNameContains(bossName)
                .sortNearest()
                .first();

        if (nearestBrutus == null)
        {
            return false;
        }

        WorldPoint playerPoint = com.tonic.data.wrappers.PlayerEx.getLocal().getWorldPoint();
        WorldPoint brutusPoint = nearestBrutus.getWorldPoint();
        if (playerPoint == null || brutusPoint == null)
        {
            return false;
        }

        return playerPoint.distanceTo(brutusPoint) <= radius;
    }

    private static String[] splitCsv(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return new String[0];
        }

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    private static int clampPercent(int value)
    {
        return Math.max(1, Math.min(99, value));
    }
}
