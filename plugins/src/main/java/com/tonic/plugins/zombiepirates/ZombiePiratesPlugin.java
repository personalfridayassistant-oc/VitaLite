package com.tonic.plugins.zombiepirates;

import com.google.inject.Provides;
import com.tonic.api.entities.NpcAPI;
import com.tonic.api.entities.TileItemAPI;
import com.tonic.api.entities.TileObjectAPI;
import com.tonic.api.game.MovementAPI;
import com.tonic.api.game.SkillAPI;
import com.tonic.api.threaded.Delays;
import com.tonic.api.widgets.BankAPI;
import com.tonic.api.widgets.EquipmentAPI;
import com.tonic.api.widgets.GrandExchangeAPI;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.data.wrappers.ItemEx;
import com.tonic.data.wrappers.NpcEx;
import com.tonic.data.wrappers.PlayerEx;
import com.tonic.data.wrappers.TileItemEx;
import com.tonic.data.wrappers.TileObjectEx;
import com.tonic.queries.NpcQuery;
import com.tonic.queries.PlayerQuery;
import com.tonic.queries.TileItemQuery;
import com.tonic.queries.TileObjectQuery;
import com.tonic.services.pathfinder.Walker;
import com.tonic.util.VitaPlugin;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.config.ConfigButtonClicked;
import net.runelite.client.events.ConfigButtonClicked;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@PluginDescriptor(
        name = "# Zombie Pirates Max Efficiency",
        description = "Automates zombie pirate banking, travel, combat/loot, and anti-PK escape",
        tags = {"zombie", "pirates", "wilderness", "automation", "money making"}
)
public class ZombiePiratesPlugin extends VitaPlugin
{
    private enum State
    {
        PREPARE_BANK,
        RESTOCK_GE,
        TRAVEL,
        COMBAT,
        ESCAPE
    }

    @Inject
    private ZombiePiratesConfig config;

    @Inject
    private Client client;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ZombiePiratesOverlay overlay;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ConfigManager configManager;

    private State state = State.PREPARE_BANK;
    private Instant startTime;
    private int kills;
    private long lootValue;
    private String tripText = "Idle";
    private final List<String> missingItems = new ArrayList<>();

    private static final String CONFIG_GROUP = "zombiePirates";
    private static final String CONFIG_GEAR_LIST = "gearList";
    private static final String CONFIG_INVENTORY_LIST = "inventoryList";
    private static final String BUTTON_COPY_GEAR = "copyGear";
    private static final String BUTTON_COPY_INVENTORY = "copyInventory";

    @Provides
    ZombiePiratesConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ZombiePiratesConfig.class);
    }

    @Override
    protected void startUp()
    {
        startTime = Instant.now();
        kills = 0;
        lootValue = 0;
        state = State.PREPARE_BANK;
        tripText = "Starting";
        missingItems.clear();
        overlayManager.add(overlay);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
    }

    @Override
    public void loop() throws Exception
    {
        if (!config.enabled())
        {
            tripText = "Disabled";
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            return;
        }

        if (shouldEscape())
        {
            state = State.ESCAPE;
        }

        if (state == State.PREPARE_BANK)
        {
            handlePrepareBank();
        }
        else if (state == State.RESTOCK_GE)
        {
            handleRestockGE();
        }
        else if (state == State.TRAVEL)
        {
            handleTravel();
        }
        else if (state == State.COMBAT)
        {
            handleCombat();
        }
        else if (state == State.ESCAPE)
        {
            handleEscape();
        }
    }

    @Subscribe
    private void onNpcDespawned(NpcDespawned event)
    {
        if (event.getNpc() != null && event.getNpc().getName() != null
                && event.getNpc().getName().toLowerCase(Locale.ROOT).contains("zombie pirate"))
        {
            ActorInteractingTracker tracker = new ActorInteractingTracker();
            if (tracker.localPlayerWasInteractingWith(event.getNpc()))
            {
                kills++;
            }
        }
    }

    @Subscribe
    private void onConfigButtonClicked(ConfigButtonClicked event)
    {
        if (!CONFIG_GROUP.equals(event.getGroup()))
        {
            return;
        }

        if (BUTTON_COPY_GEAR.equals(event.getKey()))
        {
            copyWornGearToConfig();
            return;
        }

        if (BUTTON_COPY_INVENTORY.equals(event.getKey()))
        {
            copyInventoryToConfig();
        }
    }

    private void handlePrepareBank()
    {
        tripText = "Preparing bank setup";

        if (!BankAPI.isOpen())
        {
            if (!openNearbyBank())
            {
                walkToNearestBankFallback();
            }
            Delays.tick(2);
            return;
        }

        BankAPI.depositAll();
        Delays.tick(1);

        missingItems.clear();

        for (String gear : splitCsv(config.gearList()))
        {
            if (!EquipmentAPI.isEquipped(gear))
            {
                ensureWithdrawn(gear, 1);
                ItemEx carried = InventoryAPI.getItem(gear);
                if (carried != null)
                {
                    InventoryAPI.interact(carried, "Wear", "Wield", "Equip");
                    Delays.tick(1);
                }
            }
        }

        ensureWithdrawn(config.foodName(), config.foodCount());
        ensureWithdrawn(config.restoreName(), config.restoreCount());
        ensureWithdrawn(config.teleportName(), 1);
        ensureWithdrawn(config.escapeTeleportName(), 1);

        for (Map.Entry<String, Integer> invItem : parseInventoryTargets(config.inventoryList()).entrySet())
        {
            ensureWithdrawn(invItem.getKey(), invItem.getValue());
        }

        if (!missingItems.isEmpty())
        {
            state = State.RESTOCK_GE;
            tripText = "Missing setup -> GE";
            return;
        }

        BankAPI.close();
        state = State.TRAVEL;
        Delays.tick(1);
    }

    private void handleRestockGE()
    {
        tripText = "Restocking at GE";

        if (!GrandExchangeAPI.isOpen())
        {
            if (!openNearbyGE())
            {
                useEmergencyTeleportIfPossible();
                Delays.tick(2);
                return;
            }
            Delays.tick(2);
            return;
        }

        for (String name : missingItems)
        {
            int itemId = resolveItemId(name);
            if (itemId <= 0)
            {
                continue;
            }

            int buyAmount = Math.max(10, requiredAmountFor(name));
            int price = Math.max(1, itemManager.getItemPrice(itemId) * 2);
            GrandExchangeAPI.startBuyOffer(itemId, buyAmount, price);
            Delays.tick(3);
        }

        if (GrandExchangeAPI.canCollect())
        {
            GrandExchangeAPI.collectAll();
            Delays.tick(2);
        }

        missingItems.clear();
        GrandExchangeAPI.close();
        state = State.PREPARE_BANK;
    }

    private void handleTravel()
    {
        tripText = "Travelling to pirates";

        WorldPoint target = new WorldPoint(config.chaosTempleX(), config.chaosTempleY(), 0);

        if (PlayerEx.getLocal().getWorldPoint().distanceTo(target) <= 20)
        {
            state = State.COMBAT;
            return;
        }

        ItemEx teleport = InventoryAPI.getItem(config.teleportName());
        if (teleport != null)
        {
            InventoryAPI.interactSubOp(teleport, "Rub", config.teleportAction());
            Delays.tick(4);
            return;
        }

        Walker.walkTo(target, () -> shouldEscape() || !config.enabled());
        Delays.tick(2);
    }

    private void handleCombat()
    {
        tripText = "Fighting / looting";

        if (needsRebank())
        {
            state = State.PREPARE_BANK;
            return;
        }

        if (maybeEatFood() || maybeDrinkRestore())
        {
            Delays.tick(1);
            return;
        }

        if (lootNearby())
        {
            Delays.tick(1);
            return;
        }

        NpcEx zombie = new NpcQuery()
                .withNameContains("Zombie pirate")
                .canAttack()
                .within(12)
                .sortNearest()
                .first();

        if (zombie != null)
        {
            NpcAPI.interact(zombie, "Attack");
            Delays.tick(2);
            return;
        }

        MovementAPI.walkAproxWorldPoint(new WorldPoint(config.chaosTempleX(), config.chaosTempleY(), 0), 5);
        Delays.tick(2);
    }

    private void handleEscape()
    {
        tripText = "Escaping PK threat";

        if (useEmergencyTeleportIfPossible())
        {
            state = State.PREPARE_BANK;
            Delays.tick(3);
            return;
        }

        WorldPoint safe = new WorldPoint(config.safeTileX(), config.safeTileY(), 0);
        Walker.walkTo(safe, () -> !config.enabled());
        state = State.PREPARE_BANK;
        Delays.tick(2);
    }

    private boolean openNearbyBank()
    {
        TileObjectEx booth = new TileObjectQuery().withNameContains("Bank").sortNearest().first();
        if (booth != null)
        {
            TileObjectAPI.interact(booth, "Bank", "Use", "Collect");
            return true;
        }

        NpcEx banker = new NpcQuery().withNameContains("Banker").sortNearest().first();
        if (banker != null)
        {
            NpcAPI.interact(banker, "Bank");
            return true;
        }

        return false;
    }

    private boolean openNearbyGE()
    {
        NpcEx clerk = new NpcQuery().withNameContains("Grand Exchange Clerk").sortNearest().first();
        if (clerk != null)
        {
            NpcAPI.interact(clerk, "Exchange", "Talk-to");
            return true;
        }
        return false;
    }

    private void walkToNearestBankFallback()
    {
        TileObjectEx booth = new TileObjectQuery().withNameContains("Bank").sortNearest().first();
        if (booth != null)
        {
            MovementAPI.walkAproxWorldPoint(booth.getWorldPoint(), 2);
        }
    }

    private void ensureWithdrawn(String name, int amount)
    {
        int carried = quantityInInventory(name);
        if (carried >= amount)
        {
            return;
        }

        int inBank = quantityInBank(name);
        if (inBank <= 0)
        {
            missingItems.add(name);
            return;
        }

        int toWithdraw = amount - carried;
        BankAPI.withdraw(name, Math.max(1, toWithdraw), false);
        Delays.tick(1);
    }

    private boolean lootNearby()
    {
        TileItemEx loot = new TileItemQuery()
                .withNames(splitCsv(config.lootList()))
                .within(8)
                .sortNearest()
                .first();

        if (loot == null)
        {
            return false;
        }

        int ge = Math.max(0, itemManager.getItemPrice(loot.getId()));
        if (ge > 0)
        {
            lootValue += (long) ge * Math.max(1, loot.getQuantity());
        }

        TileItemAPI.interact(loot, "Take");
        return true;
    }

    private boolean maybeEatFood()
    {
        int max = SkillAPI.getLevel(Skill.HITPOINTS);
        int current = SkillAPI.getBoostedLevel(Skill.HITPOINTS);
        int hpPercent = max <= 0 ? 100 : (int) Math.round((current * 100.0) / max);
        if (hpPercent > config.eatAtHpPercent())
        {
            return false;
        }

        ItemEx food = InventoryAPI.getItem(config.foodName());
        if (food == null)
        {
            return false;
        }

        InventoryAPI.interact(food, "Eat", "Drink");
        return true;
    }

    private boolean maybeDrinkRestore()
    {
        int prayer = SkillAPI.getBoostedLevel(Skill.PRAYER);
        if (prayer > 15)
        {
            return false;
        }

        ItemEx restore = InventoryAPI.getItem(config.restoreName());
        if (restore == null)
        {
            return false;
        }

        InventoryAPI.interact(restore, "Drink");
        return true;
    }

    private boolean shouldEscape()
    {
        int max = SkillAPI.getLevel(Skill.HITPOINTS);
        int current = SkillAPI.getBoostedLevel(Skill.HITPOINTS);
        int hpPercent = max <= 0 ? 100 : (int) Math.round((current * 100.0) / max);
        if (hpPercent <= config.escapeAtHpPercent())
        {
            return true;
        }

        PlayerEx hostileNearby = new PlayerQuery()
                .within(config.pkerRadius())
                .keepIf(p -> p.getPlayer() != null
                        && p.getPlayer().getName() != null
                        && !Objects.equals(p.getPlayer().getName(), client.getLocalPlayer().getName()))
                .sortNearest()
                .first();

        return hostileNearby != null;
    }

    private boolean needsRebank()
    {
        return quantityInInventory(config.foodName()) <= 0
                || quantityInInventory(config.restoreName()) <= 0
                || InventoryAPI.getEmptySlots() <= config.rebankOnSlots();
    }

    private boolean useEmergencyTeleportIfPossible()
    {
        ItemEx teleport = InventoryAPI.getItem(config.escapeTeleportName());
        if (teleport == null)
        {
            return false;
        }

        String action = config.escapeTeleportAction();
        if (action == null || action.isBlank())
        {
            InventoryAPI.interact(teleport, "Teleport", "Break", "Rub", "Commune");
        }
        else
        {
            InventoryAPI.interact(teleport, action, "Teleport", "Break", "Rub", "Commune");
        }
        return true;
    }

    private int quantityInInventory(String name)
    {
        return InventoryAPI.search()
                .withNameContains(name)
                .collect()
                .stream()
                .mapToInt(ItemEx::getQuantity)
                .sum();
    }

    private int quantityInBank(String name)
    {
        return BankAPI.search()
                .withNameContains(name)
                .collect()
                .stream()
                .mapToInt(ItemEx::getQuantity)
                .sum();
    }

    private int requiredAmountFor(String name)
    {
        if (name.equalsIgnoreCase(config.foodName())) return config.foodCount();
        if (name.toLowerCase(Locale.ROOT).contains(config.restoreName().toLowerCase(Locale.ROOT))) return config.restoreCount();
        if (name.equalsIgnoreCase(config.teleportName())) return 5;
        if (name.equalsIgnoreCase(config.escapeTeleportName())) return 5;
        return 1;
    }


    private int resolveItemId(String name)
    {
        String lowered = name.toLowerCase(Locale.ROOT);
        for (String mapping : splitCsv(config.restockMappings()))
        {
            String[] parts = mapping.split(":");
            if (parts.length != 2)
            {
                continue;
            }

            String mappedName = parts[0].trim().toLowerCase(Locale.ROOT);
            if (!mappedName.isEmpty() && lowered.contains(mappedName))
            {
                try
                {
                    return Integer.parseInt(parts[1].trim());
                }
                catch (NumberFormatException ignored)
                {
                    return -1;
                }
            }
        }
        return -1;
    }

    private Map<String, Integer> parseInventoryTargets(String csv)
    {
        Map<String, Integer> targets = new LinkedHashMap<>();
        if (csv == null || csv.isBlank())
        {
            return targets;
        }

        for (String token : splitCsv(csv))
        {
            String[] parts = token.split(":");
            if (parts.length != 2)
            {
                continue;
            }

            String itemName = parts[0].trim();
            if (itemName.isEmpty())
            {
                continue;
            }

            try
            {
                int amount = Integer.parseInt(parts[1].trim());
                if (amount > 0)
                {
                    targets.put(itemName, amount);
                }
            }
            catch (NumberFormatException ignored)
            {
            }
        }

        return targets;
    }

    private void copyWornGearToConfig()
    {
        String joinedGear = EquipmentAPI.getAll().stream()
                .map(ItemEx::getName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        configManager.setConfiguration(CONFIG_GROUP, CONFIG_GEAR_LIST, joinedGear);
    }

    private void copyInventoryToConfig()
    {
        Map<String, Integer> grouped = new LinkedHashMap<>();
        for (ItemEx item : InventoryAPI.getItems())
        {
            if (item == null || item.getName() == null)
            {
                continue;
            }

            String name = item.getName().trim();
            if (name.isEmpty())
            {
                continue;
            }

            grouped.merge(name, Math.max(1, item.getQuantity()), Integer::sum);
        }

        String joinedInventory = grouped.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        configManager.setConfiguration(CONFIG_GROUP, CONFIG_INVENTORY_LIST, joinedInventory);
    }

    private String[] splitCsv(String csv)
    {
        if (csv == null || csv.isBlank())
        {
            return new String[0];
        }

        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    public int getKills()
    {
        return kills;
    }

    public long getLootValue()
    {
        return lootValue;
    }

    public String getStateText()
    {
        return state.name();
    }

    public String getTripText()
    {
        return tripText;
    }

    public String getRuntimeText()
    {
        Duration duration = Duration.between(startTime, Instant.now());
        long h = duration.toHours();
        long m = duration.minusHours(h).toMinutes();
        long s = duration.minusHours(h).minusMinutes(m).getSeconds();
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    private static class ActorInteractingTracker
    {
        boolean localPlayerWasInteractingWith(net.runelite.api.NPC npc)
        {
            if (npc == null || PlayerEx.getLocal() == null || PlayerEx.getLocal().getInteracting() == null)
            {
                return false;
            }

            String interactingName = PlayerEx.getLocal().getInteracting().getName();
            return interactingName != null && npc.getName() != null
                    && interactingName.equalsIgnoreCase(npc.getName());
        }
    }
}
