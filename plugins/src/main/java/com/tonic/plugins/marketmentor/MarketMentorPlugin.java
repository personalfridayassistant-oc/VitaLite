package com.tonic.plugins.marketmentor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import com.tonic.Logger;
import com.tonic.api.entities.NpcAPI;
import com.tonic.api.game.ClientScriptAPI;
import com.tonic.api.game.WorldsAPI;
import com.tonic.api.threaded.Delays;
import com.tonic.api.widgets.GrandExchangeAPI;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.data.wrappers.ItemEx;
import com.tonic.data.wrappers.NpcEx;
import com.tonic.queries.NpcQuery;
import com.tonic.util.MessageUtil;
import com.tonic.util.VitaPlugin;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@PluginDescriptor(
        name = "# Market Mentor",
        description = "Automated GE suggestions and offer placement using OSRS Wiki market APIs",
        tags = {"ge", "merchanting", "flipping", "automation"}
)
public class MarketMentorPlugin extends VitaPlugin
{
    private static final String WIKI_LATEST = "https://prices.runescape.wiki/api/v1/osrs/latest";
    private static final String WIKI_MAPPING = "https://prices.runescape.wiki/api/v1/osrs/mapping";
    private static final double ESTIMATED_GE_TAX = 0.01;
    private static final int MARKET_REFRESH_SECONDS = 8;

    public static final class PanelOffer
    {
        private final int itemId;
        private final String name;
        private final String roiText;
        private final String volumeText;
        private final String spreadText;

        public PanelOffer(int itemId, String name, String roiText, String volumeText, String spreadText)
        {
            this.itemId = itemId;
            this.name = name;
            this.roiText = roiText;
            this.volumeText = volumeText;
            this.spreadText = spreadText;
        }

        public int getItemId() { return itemId; }
        public String getName() { return name; }
        public String getRoiText() { return roiText; }
        public String getVolumeText() { return volumeText; }
        public String getSpreadText() { return spreadText; }
    }

    private static final class MarketSnapshot
    {
        private final int itemId;
        private final int high;
        private final int low;
        private final int highTime;
        private final int lowTime;
        private final int highVolume;
        private final int lowVolume;

        private MarketSnapshot(int itemId, int high, int low, int highTime, int lowTime, int highVolume, int lowVolume)
        {
            this.itemId = itemId;
            this.high = high;
            this.low = low;
            this.highTime = highTime;
            this.lowTime = lowTime;
            this.highVolume = highVolume;
            this.lowVolume = lowVolume;
        }

        private int minVolume()
        {
            return Math.min(highVolume, lowVolume);
        }
    }

    private static final class Opportunity
    {
        private final int itemId;
        private final int buyPrice;
        private final int normalSellPrice;
        private final int panicSellPrice;
        private final int targetQuantity;
        private final int spread;
        private final int volume;
        private final double netRoi;
        private final double score;

        private Opportunity(int itemId, int buyPrice, int normalSellPrice, int panicSellPrice, int targetQuantity, int spread, int volume, double netRoi, double score)
        {
            this.itemId = itemId;
            this.buyPrice = buyPrice;
            this.normalSellPrice = normalSellPrice;
            this.panicSellPrice = panicSellPrice;
            this.targetQuantity = targetQuantity;
            this.spread = spread;
            this.volume = volume;
            this.netRoi = netRoi;
            this.score = score;
        }
    }

    private static final class Position
    {
        private int quantity;
        private long totalCost;
        private int lastBuyPrice;
        private int lastSellPrice;
        private final LinkedList<BuyLot> buyLots = new LinkedList<>();
    }

    private static final class BuyLot
    {
        private int quantity;
        private final int unitPrice;

        private BuyLot(int quantity, int unitPrice)
        {
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }
    }

    @Inject private MarketMentorConfig config;
    @Inject private Client client;
    @Inject private OverlayManager overlayManager;
    @Inject private MarketMentorOverlay overlay;
    @Inject private MarketMentorPanel panel;
    @Inject private ClientToolbar clientToolbar;

    private NavigationButton navigationButton;

    private final Map<Integer, Instant> activeOfferSince = new HashMap<>();
    private final Map<Integer, Instant> holdingsSince = new HashMap<>();
    private final Map<Integer, Integer> lastInventory = new HashMap<>();
    private final Map<Integer, Position> positions = new HashMap<>();
    private final Set<Integer> trackedItemIds = new HashSet<>();
    private final List<PanelOffer> panelOffers = new ArrayList<>();
    private final Map<Integer, Boolean> wikiMembershipMap = new HashMap<>();
    private final Map<Integer, Integer> wikiGeLimitMap = new HashMap<>();
    private final Map<Integer, String> wikiItemNameMap = new HashMap<>();
    private final Map<Integer, Instant> buyLimitLockoutUntil = new HashMap<>();
    private final Map<Integer, Integer> pendingBuyPrice = new HashMap<>();
    private final Map<Integer, Integer> pendingSellPrice = new HashMap<>();

    private Instant lastMappingRefresh = Instant.EPOCH;
    private Instant lastCollectAt = Instant.EPOCH;
    private Instant lastWebhookAt = Instant.EPOCH;
    private Instant startTime;
    private long gpMade;
    private long itemsFlipped;
    private long closedTrades;
    private long profitableTrades;
    private long losingTrades;
    private int lastTradedItemId;
    private String statusText = "Idle";
    private String slotText = "0/0";
    private String coinsText = "0";
    private Opportunity currentSuggestion;
    private Opportunity bestSuggestion;
    private int lastAnnouncedSuggestionId = -1;
    private final Map<Integer, Double> itemPriorityMemory = new HashMap<>();
    private final Set<Integer> blacklistIds = new HashSet<>();
    private List<MarketSnapshot> cachedSnapshots = new ArrayList<>();
    private Instant lastMarketFetchAt = Instant.EPOCH;

    @Provides
    MarketMentorConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(MarketMentorConfig.class);
    }

    @Override
    protected void startUp()
    {
        startTime = Instant.now();
        gpMade = 0;
        itemsFlipped = 0;
        closedTrades = 0;
        profitableTrades = 0;
        losingTrades = 0;
        lastTradedItemId = -1;
        statusText = "Starting";
        currentSuggestion = null;
        bestSuggestion = null;
        lastAnnouncedSuggestionId = -1;
        cachedSnapshots = new ArrayList<>();
        lastMarketFetchAt = Instant.EPOCH;
        overlayManager.add(overlay);

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/graph.png");
        navigationButton = NavigationButton.builder().tooltip("Market Mentor").icon(icon).panel(panel).build();
        clientToolbar.addNavigation(navigationButton);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
            navigationButton = null;
        }
        statusText = "Stopped";
    }

    @Override
    public void loop() throws Exception
    {
        if (!config.enabled() || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            statusText = "Disabled / not logged in";
            refreshPanel();
            return;
        }

        refreshMappingIfStale();
        blacklistIds.clear();
        blacklistIds.addAll(parseBlacklist(config.blacklistItemIds()));
        buyLimitLockoutUntil.entrySet().removeIf(e -> Instant.now().isAfter(e.getValue()));
        updatePnlTracking();
        updateSlotText();
        coinsText = String.format("%,d", inventoryAmount(ItemID.COINS));
        clearNumericDialogueFailsafe();

        if (!GrandExchangeAPI.isOpen())
        {
            statusText = "Opening GE";
            if (config.autoOpenGe())
            {
                openGrandExchange();
                humanPauseQuick();
            }
            refreshPanel();
            Delays.tick(Math.max(1, config.loopDelayTicks()));
            return;
        }

        collectImmediate();
        pruneAndCancelStaleOffers();
        collectImmediate();

        List<MarketSnapshot> snapshots = getMarketSnapshots();
        Map<Integer, MarketSnapshot> snapshotById = new HashMap<>();
        for (MarketSnapshot snapshot : snapshots)
        {
            snapshotById.put(snapshot.itemId, snapshot);
        }
        List<Opportunity> opportunities = buildOpportunities(snapshots, false);
        if (opportunities.isEmpty())
        {
            opportunities = buildOpportunities(snapshots, true); // failsafe relaxed strategy
            if (!opportunities.isEmpty())
            {
                statusText = "Using relaxed failsafe strategy";
            }
        }
        if (opportunities.isEmpty())
        {
            opportunities = buildFallbackSpreadOpportunities(snapshots);
            if (!opportunities.isEmpty())
            {
                statusText = "Using spread-only failsafe strategy";
            }
        }

        opportunities.sort(Comparator.comparingDouble((Opportunity o) -> o.score).reversed());
        updatePanelOffers(opportunities);
        Map<Integer, Opportunity> opportunityById = new HashMap<>();
        for (Opportunity o : opportunities)
        {
            opportunityById.put(o.itemId, o);
        }

        collectImmediate();

        if (opportunities.isEmpty())
        {
            statusText = "No viable opportunities";
            currentSuggestion = null;
            refreshPanel();
            Delays.tick(Math.max(1, config.loopDelayTicks()));
            return;
        }

        currentSuggestion = opportunities.get(0);
        announceSuggestionIfChanged(currentSuggestion);
        if (bestSuggestion == null || currentSuggestion.netRoi > bestSuggestion.netRoi)
        {
            bestSuggestion = currentSuggestion;
        }

        trackTopItems(opportunities);
        int actions = forceExitBadDeals(opportunityById, snapshotById);
        actions += executeTrades(opportunities, snapshotById);
        if (actions == 0)
        {
            statusText = "No actionable trade";
            // extra failsafe: try top opportunity with minimal quantity if affordable
            Opportunity o = opportunities.get(0);
            if (!hasActiveOffer(o.itemId))
            {
                int coins = Math.max(0, inventoryAmount(ItemID.COINS) - Math.max(0, config.coinReserve()));
                if (coins >= o.buyPrice && submitBuy(o, 1))
                {
                    lastTradedItemId = o.itemId;
                    statusText = "Failsafe buy " + itemName(o.itemId);
                }
            }
        }

        maybeSendDiscordSummary();
        refreshPanel();
        clearNumericDialogueFailsafe();
        Delays.tick(Math.max(1, config.loopDelayTicks()));
    }

    private int executeTrades(List<Opportunity> opportunities, Map<Integer, MarketSnapshot> snapshotById)
    {
        int freeSlots = getFreeEligibleSlots();
        freeSlots = Math.max(0, freeSlots - Math.max(0, config.reservedSlots()));
        if (freeSlots <= 0)
        {
            statusText = "No free slots (after reserve)";
            return 0;
        }

        int actions = 0;

        // SELL PRIORITY #1: sell any held inventory first, even from partial/completed buys.
        for (Opportunity opportunity : opportunities)
        {
            if (freeSlots <= 0)
            {
                break;
            }
            if (hasActiveOffer(opportunity.itemId))
            {
                continue;
            }

            int invAmount = inventoryAmount(opportunity.itemId);
            if (invAmount <= 0)
            {
                continue;
            }

            if (submitSell(opportunity, invAmount))
            {
                actions++;
                freeSlots--;
                lastTradedItemId = opportunity.itemId;
                activeOfferSince.put(opportunity.itemId, Instant.now());
                statusText = "Selling " + itemName(opportunity.itemId);
                collectFast();
                humanPauseQuick();
            }
        }

        // Additional inventory-first liquidation pass: if any inventory item has market data, sell it before buying.
        if (freeSlots > 0)
        {
            for (int itemId : getInventoryTradeableIds())
            {
                if (freeSlots <= 0 || itemId == ItemID.COINS || hasActiveOffer(itemId))
                {
                    continue;
                }

                int invAmount = inventoryAmount(itemId);
                if (invAmount <= 0)
                {
                    continue;
                }

                MarketSnapshot snapshot = snapshotById.get(itemId);
                if (snapshot == null)
                {
                    continue;
                }

                int buyRef = Math.max(1, snapshot.low);
                int sellRef = Math.max(1, applyPercent(snapshot.high, -Math.abs(config.sellUndercutPct()), false));
                Opportunity invSell = new Opportunity(itemId, buyRef, sellRef, Math.max(1, buyRef), invAmount, Math.max(1, snapshot.high - snapshot.low), Math.max(1, snapshot.minVolume()), 0.0, 0.0);
                if (submitSell(invSell, invAmount))
                {
                    actions++;
                    freeSlots--;
                    lastTradedItemId = itemId;
                    activeOfferSince.put(itemId, Instant.now());
                    statusText = "Inventory sell " + itemName(itemId);
                    collectImmediate();
                    humanPauseQuick();
                }
            }
        }

        // Backstop: if still free slots, sell any remaining tracked inventory with fallback prices.
        if (freeSlots > 0)
        {
            for (int itemId : new HashSet<>(trackedItemIds))
            {
                if (freeSlots <= 0 || hasActiveOffer(itemId))
                {
                    continue;
                }

                int invAmount = inventoryAmount(itemId);
                if (invAmount <= 0)
                {
                    continue;
                }

                MarketSnapshot snapshot = snapshotById.get(itemId);
                int buyRef = snapshot != null ? Math.max(1, snapshot.low) : Math.max(1, pendingBuyPrice.getOrDefault(itemId, 1));
                int sellRef = snapshot != null ? Math.max(1, applyPercent(snapshot.high, -Math.abs(config.sellUndercutPct()), false)) : Math.max(1, pendingSellPrice.getOrDefault(itemId, pendingBuyPrice.getOrDefault(itemId, 1)));
                Opportunity fallback = new Opportunity(itemId, buyRef, sellRef, Math.max(1, buyRef), invAmount, 1, Math.max(1, invAmount), 0.0, 0.0);
                if (submitSell(fallback, invAmount))
                {
                    actions++;
                    freeSlots--;
                    lastTradedItemId = itemId;
                    activeOfferSince.put(itemId, Instant.now());
                    statusText = "Selling held " + itemName(itemId);
                    collectImmediate();
                    humanPauseQuick();
                }
            }
        }

        int coins = Math.max(0, inventoryAmount(ItemID.COINS) - Math.max(0, config.coinReserve()));
        if (coins <= 0 || freeSlots <= 0)
        {
            return actions;
        }

        List<Opportunity> buyList = new ArrayList<>();
        for (Opportunity opportunity : opportunities)
        {
            if (hasActiveOffer(opportunity.itemId) || inventoryAmount(opportunity.itemId) >= opportunity.targetQuantity)
            {
                continue;
            }
            buyList.add(opportunity);
        }

        int buyTargets = Math.min(freeSlots, buyList.size());
        for (int i = 0; i < buyTargets; i++)
        {
            Opportunity opportunity = buyList.get(i);
            int needed = Math.max(0, opportunity.targetQuantity - inventoryAmount(opportunity.itemId));
            if (needed <= 0)
            {
                continue;
            }

            int splitsLeft = Math.max(1, buyTargets - i);
            int splitBudget = Math.max(1, coins / splitsLeft);
            int riskCap = (int) Math.max(1, Math.floor(coins * (Math.max(1.0, config.maxBudgetPct()) / 100.0)));
            int budget = Math.min(Math.min(splitBudget, riskCap), Math.max(1, config.maxGpPerTrade()));
            int qty = Math.min(needed, budget / Math.max(1, opportunity.buyPrice));
            if (qty <= 0)
            {
                if (coins >= opportunity.buyPrice)
                {
                    qty = 1;
                }
                else
                {
                    continue; // try next item instead of stalling
                }
            }

            if (submitBuy(opportunity, qty))
            {
                actions++;
                freeSlots--;
                lastTradedItemId = opportunity.itemId;
                coins = Math.max(0, coins - (qty * opportunity.buyPrice));
                activeOfferSince.put(opportunity.itemId, Instant.now());
                statusText = "Buying " + itemName(opportunity.itemId);
                collectFast();
                humanPauseQuick();
            }
        }

        return actions;
    }

    private int forceExitBadDeals(Map<Integer, Opportunity> opportunityById, Map<Integer, MarketSnapshot> snapshotById)
    {
        int actions = 0;
        for (int itemId : new HashSet<>(trackedItemIds))
        {
            if (hasActiveOffer(itemId))
            {
                continue;
            }

            int invAmount = inventoryAmount(itemId);
            if (invAmount <= 0)
            {
                continue;
            }

            Position p = positions.computeIfAbsent(itemId, k -> new Position());
            int avgCost = p.quantity > 0 ? (int) Math.max(1L, p.totalCost / Math.max(1, p.quantity)) : Math.max(1, p.lastBuyPrice);
            Opportunity o = opportunityById.get(itemId);
            boolean badDeal = o == null || o.normalSellPrice <= avgCost || o.netRoi < 0;
            if (!badDeal)
            {
                continue;
            }

            int sellPrice = o != null ? Math.max(1, o.panicSellPrice) : Math.max(1, avgCost - 1);
            MarketSnapshot snap = snapshotById.get(itemId);
            if (snap != null)
            {
                sellPrice = Math.max(1, applyPercent(snap.high, -Math.abs(config.sellUndercutPct()), false));
            }
            Opportunity exit = new Opportunity(itemId, avgCost, sellPrice, sellPrice, invAmount, 1, invAmount, 0, 0);
            if (submitSell(exit, invAmount))
            {
                actions++;
                statusText = "Exiting bad deal " + itemName(itemId);
                activeOfferSince.put(itemId, Instant.now());
                lastTradedItemId = itemId;
            }
        }
        return actions;
    }

    private void collectImmediate()
    {
        if (!GrandExchangeAPI.canCollect())
        {
            return;
        }

        if (Duration.between(lastCollectAt, Instant.now()).toMillis() < 140)
        {
            return;
        }

        for (int i = 0; i < 2 && GrandExchangeAPI.canCollect(); i++)
        {
            GrandExchangeAPI.collectAll();
            clearNumericDialogueFailsafe();
            Delays.wait(20);
        }

        lastCollectAt = Instant.now();
    }

    private void clearNumericDialogueFailsafe()
    {
        for (int i = 0; i < 3; i++)
        {
            ClientScriptAPI.closeNumericInputDialogue();
            Delays.wait(15);
        }
    }

    private List<Opportunity> buildOpportunities(List<MarketSnapshot> snapshots, boolean relaxed)
    {
        List<Opportunity> opportunities = new ArrayList<>();
        int minVolume = Math.max(1, config.minFiveMinuteVolume());
        double minMargin = Math.max(0.1, config.minMarginPct()) / 100.0;
        double minNetRoi = Math.max(0.1, config.minNetRoiPct()) / 100.0;
        int maxAgeSeconds = Math.max(60, config.maxDataAgeSeconds());

        if (relaxed)
        {
            minVolume = Math.max(1, minVolume / 2);
            minMargin *= 0.6;
            minNetRoi *= 0.5;
            maxAgeSeconds *= 2;
        }

        long now = System.currentTimeMillis() / 1000L;

        for (MarketSnapshot snap : snapshots)
        {
            if (blacklistIds.contains(snap.itemId) || !isItemBuyableForCurrentAccount(snap.itemId) || snap.low <= 0 || snap.high <= snap.low)
            {
                continue;
            }

            if (minVolume > 1 && snap.minVolume() < minVolume)
            {
                continue;
            }

            int freshest = Math.max(snap.highTime, snap.lowTime);
            if ((now - freshest) > maxAgeSeconds)
            {
                continue;
            }

            double grossMargin = (double) (snap.high - snap.low) / (double) snap.low;
            if (grossMargin < minMargin)
            {
                continue;
            }

            int buyPrice = applyPercent(snap.low, config.buyBumpPct(), true);
            int sellPrice = Math.max(buyPrice + 1, applyPercent(snap.high, -Math.abs(config.sellUndercutPct()), false));
            int panicSell = Math.max(1, applyPercent(snap.low, -Math.abs(config.panicDiscountPct()), false));

            int grossProfit = sellPrice - buyPrice;
            if (grossProfit < Math.max(0, config.minProfitMarginGp()))
            {
                continue;
            }

            double netPerItem = (sellPrice * (1.0 - ESTIMATED_GE_TAX)) - buyPrice;
            double netRoi = netPerItem / Math.max(1.0, buyPrice);
            if (netRoi < minNetRoi)
            {
                continue;
            }

            int volume = snap.minVolume();
            int spread = Math.max(1, snap.high - snap.low);
            int targetQuantity = Math.max(1, Math.min(volume / 2, Math.max(1, config.maxGpPerTrade()) / Math.max(1, buyPrice)));
            double memoryBoost = 1.0 + Math.max(-0.5, Math.min(2.5, itemPriorityMemory.getOrDefault(snap.itemId, 0.0)));
            double score = ((netPerItem * Math.log10(Math.max(10, volume))) + (spread * 0.01)) * memoryBoost;
            opportunities.add(new Opportunity(snap.itemId, buyPrice, sellPrice, panicSell, targetQuantity, spread, volume, netRoi, score));
        }

        return opportunities;
    }


    private List<Opportunity> buildFallbackSpreadOpportunities(List<MarketSnapshot> snapshots)
    {
        List<Opportunity> opportunities = new ArrayList<>();
        for (MarketSnapshot snap : snapshots)
        {
            if (blacklistIds.contains(snap.itemId) || !isItemBuyableForCurrentAccount(snap.itemId) || snap.low <= 0 || snap.high <= snap.low)
            {
                continue;
            }

            int volume = Math.max(1, snap.minVolume());
            int spread = Math.max(1, snap.high - snap.low);
            int buyPrice = Math.max(1, snap.low);
            int sellPrice = Math.max(buyPrice + 1, snap.high);
            int grossProfit = sellPrice - buyPrice;
            if (grossProfit < Math.max(0, config.minProfitMarginGp()))
            {
                continue;
            }
            int qty = Math.max(1, Math.min(5, Math.max(1, config.maxGpPerTrade()) / buyPrice));
            double netRoi = (((sellPrice * (1.0 - ESTIMATED_GE_TAX)) - buyPrice) / Math.max(1.0, buyPrice));
            double score = spread * Math.log10(Math.max(10, volume));
            opportunities.add(new Opportunity(snap.itemId, buyPrice, sellPrice, buyPrice, qty, spread, volume, netRoi, score));
        }
        return opportunities;
    }

    private List<MarketSnapshot> getMarketSnapshots()
    {
        if (cachedSnapshots.isEmpty() || Duration.between(lastMarketFetchAt, Instant.now()).getSeconds() >= MARKET_REFRESH_SECONDS)
        {
            cachedSnapshots = fetchSnapshots();
            lastMarketFetchAt = Instant.now();
        }
        return cachedSnapshots;
    }

    private List<MarketSnapshot> fetchSnapshots()
    {
        List<MarketSnapshot> snapshots = new ArrayList<>();
        try
        {
            JsonObject latestData = readJson(WIKI_LATEST).getAsJsonObject().getAsJsonObject("data");
            if (latestData == null)
            {
                return snapshots;
            }

            for (Map.Entry<String, JsonElement> entry : latestData.entrySet())
            {
                int itemId;
                try
                {
                    itemId = Integer.parseInt(entry.getKey());
                }
                catch (Exception ignored)
                {
                    continue;
                }

                JsonObject latest = entry.getValue().getAsJsonObject();
                int high = getInt(latest, "high", 0);
                int low = getInt(latest, "low", 0);
                if (high <= 0 || low <= 0)
                {
                    continue;
                }

                int pseudoVolume = Math.max(1, wikiGeLimitMap.getOrDefault(itemId, 1));
                snapshots.add(new MarketSnapshot(
                        itemId,
                        high,
                        low,
                        getInt(latest, "highTime", 0),
                        getInt(latest, "lowTime", 0),
                        pseudoVolume,
                        pseudoVolume
                ));
            }

            snapshots.sort(Comparator.comparingInt((MarketSnapshot s) -> (s.high - s.low)).reversed());
            if (snapshots.size() > Math.max(250, config.maxUniverseItems()))
            {
                snapshots = new ArrayList<>(snapshots.subList(0, Math.max(250, config.maxUniverseItems())));
            }
        }
        catch (Exception ex)
        {
            Logger.warn("[MarketMentor] market fetch failed: " + ex.getMessage());
        }

        return snapshots;
    }

    // legacy helper retained for compatibility with older code paths.
    private List<Integer> selectCandidateIds(JsonObject fiveMinuteData, int maxItems)
    {
        return Collections.emptyList();
    }

    private void refreshMappingIfStale()
    {
        if (Duration.between(lastMappingRefresh, Instant.now()).getSeconds() < 1800 && !wikiMembershipMap.isEmpty())
        {
            return;
        }

        try
        {
            JsonArray mappingArray = readJson(WIKI_MAPPING).getAsJsonArray();
            wikiMembershipMap.clear();
            wikiGeLimitMap.clear();
            wikiItemNameMap.clear();
            for (JsonElement element : mappingArray)
            {
                JsonObject obj = element.getAsJsonObject();
                int id = getInt(obj, "id", -1);
                if (id <= 0)
                {
                    continue;
                }
                boolean members = obj.has("members") && !obj.get("members").isJsonNull() && obj.get("members").getAsBoolean();
                int geLimit = getInt(obj, "limit", 0);
                String name = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : null;
                wikiMembershipMap.put(id, members);
                wikiGeLimitMap.put(id, geLimit);
                if (name != null && !name.trim().isEmpty())
                {
                    wikiItemNameMap.put(id, name);
                }
            }
            lastMappingRefresh = Instant.now();
        }
        catch (Exception ex)
        {
            Logger.warn("[MarketMentor] mapping fetch failed: " + ex.getMessage());
        }
    }

    private boolean submitBuy(Opportunity opportunity, int quantity)
    {
        Instant lockout = buyLimitLockoutUntil.get(opportunity.itemId);
        if (lockout != null && Instant.now().isBefore(lockout))
        {
            return false;
        }

        Integer geLimit = wikiGeLimitMap.get(opportunity.itemId);
        if (geLimit != null && geLimit > 0)
        {
            int inventory = inventoryAmount(opportunity.itemId);
            if (inventory >= geLimit)
            {
                buyLimitLockoutUntil.put(opportunity.itemId, Instant.now().plus(Duration.ofHours(4)));
                MessageUtil.sendChatMessage("[Market Mentor] Buy limit reached for " + itemName(opportunity.itemId) + ". Skipping for now.");
                return false;
            }
        }

        Position position = positions.computeIfAbsent(opportunity.itemId, k -> new Position());
        position.lastBuyPrice = opportunity.buyPrice;
        pendingBuyPrice.put(opportunity.itemId, opportunity.buyPrice);
        clearNumericDialogueFailsafe();
        boolean ok = GrandExchangeAPI.startBuyOffer(opportunity.itemId, quantity, opportunity.buyPrice) != null;
        clearNumericDialogueFailsafe();
        if (!ok)
        {
            buyLimitLockoutUntil.put(opportunity.itemId, Instant.now().plus(Duration.ofHours(4)));
            MessageUtil.sendChatMessage("[Market Mentor] Unable to buy " + itemName(opportunity.itemId) + " (possible GE buy limit). Added cooldown.");
        }
        return ok;
    }

    private boolean submitSell(Opportunity opportunity, int inventoryAmount)
    {
        holdingsSince.putIfAbsent(opportunity.itemId, Instant.now());
        boolean timedOut = Duration.between(holdingsSince.get(opportunity.itemId), Instant.now()).getSeconds() >= Math.max(30, config.holdingTimeoutSeconds());
        int sellPrice = timedOut ? opportunity.panicSellPrice : opportunity.normalSellPrice;
        pendingSellPrice.put(opportunity.itemId, sellPrice);
        int quantity = Math.max(1, inventoryAmount);
        if (quantity <= 0)
        {
            return false;
        }

        Position position = positions.computeIfAbsent(opportunity.itemId, k -> new Position());
        position.lastSellPrice = sellPrice;
        clearNumericDialogueFailsafe();
        boolean ok = GrandExchangeAPI.startSellOffer(opportunity.itemId, quantity, sellPrice) != null;
        clearNumericDialogueFailsafe();
        return ok;
    }

    private void pruneAndCancelStaleOffers()
    {
        int timeoutSeconds = Math.max(30, config.staleOfferSeconds());
        Instant now = Instant.now();

        for (GrandExchangeOffer offer : GrandExchangeAPI.getOffers())
        {
            if (offer == null || offer.getItemId() <= 0)
            {
                continue;
            }

            trackedItemIds.add(offer.getItemId());
            if (!isActiveState(offer.getState()))
            {
                activeOfferSince.remove(offer.getItemId());
                continue;
            }

            activeOfferSince.putIfAbsent(offer.getItemId(), now);
            Instant start = activeOfferSince.get(offer.getItemId());
            if (start != null && Duration.between(start, now).getSeconds() >= timeoutSeconds)
            {
                GrandExchangeAPI.abortOffer(offer.getItemId());
                clearNumericDialogueFailsafe();
                humanPauseQuick();
                activeOfferSince.put(offer.getItemId(), now);
            }
        }
    }


    private void announceSuggestionIfChanged(Opportunity suggestion)
    {
        if (suggestion == null || suggestion.itemId <= 0 || suggestion.itemId == lastAnnouncedSuggestionId)
        {
            return;
        }

        lastAnnouncedSuggestionId = suggestion.itemId;
        MessageUtil.sendChatMessage("[Market Mentor] New suggestion: " + itemName(suggestion.itemId)
                + " | ROI " + String.format("%.2f%%", suggestion.netRoi * 100.0)
                + " | Spread " + String.format("%,d", suggestion.spread)
                + " | Vol " + String.format("%,d", suggestion.volume));
    }

    private void trackTopItems(List<Opportunity> opportunities)
    {
        int keepTop = Math.max(10, config.maxItemsPerCycle() * 10);
        for (int i = 0; i < Math.min(keepTop, opportunities.size()); i++)
        {
            trackedItemIds.add(opportunities.get(i).itemId);
        }
    }

    private void updatePanelOffers(List<Opportunity> opportunities)
    {
        panelOffers.clear();
        int top = Math.min(8, opportunities.size());
        for (int i = 0; i < top; i++)
        {
            Opportunity o = opportunities.get(i);
            panelOffers.add(new PanelOffer(o.itemId, itemName(o.itemId), String.format("%.2f%%", o.netRoi * 100.0), String.format("%,d", o.volume), String.format("%,d", o.spread)));
        }
    }

    private void refreshPanel()
    {
        panel.refresh(statusText, getProfitText(), getAverageGpPerHourText(), (int) itemsFlipped, lastTradedItemId, itemName(lastTradedItemId), new ArrayList<>(panelOffers));
    }

    private void openGrandExchange()
    {
        NpcEx clerk = new NpcQuery().withNameContains("Grand Exchange Clerk").sortNearest().first();
        if (clerk != null)
        {
            NpcAPI.interact(clerk, "Exchange", "Talk-to", "Bank");
        }
    }

    private void updatePnlTracking()
    {
        Set<Integer> keys = new HashSet<>(trackedItemIds);
        keys.addAll(lastInventory.keySet());

        for (int itemId : keys)
        {
            int current = inventoryAmount(itemId);
            int previous = lastInventory.getOrDefault(itemId, current);
            int delta = current - previous;

            Position position = positions.computeIfAbsent(itemId, k -> new Position());
            if (delta > 0)
            {
                int price = Math.max(1, pendingBuyPrice.getOrDefault(itemId, position.lastBuyPrice));
                position.lastBuyPrice = price;
                position.quantity += delta;
                position.totalCost += (long) delta * price;
                position.buyLots.add(new BuyLot(delta, price));
                holdingsSince.putIfAbsent(itemId, Instant.now());
            }
            else if (delta < 0)
            {
                int sold = -delta;
                int sellPrice = Math.max(1, pendingSellPrice.getOrDefault(itemId, position.lastSellPrice));
                position.lastSellPrice = sellPrice;

                int remaining = sold;
                long realized = 0;
                while (remaining > 0 && !position.buyLots.isEmpty())
                {
                    BuyLot lot = position.buyLots.peekFirst();
                    int use = Math.min(remaining, lot.quantity);
                    realized += (long) use * (sellPrice - lot.unitPrice);
                    lot.quantity -= use;
                    remaining -= use;
                    if (lot.quantity <= 0)
                    {
                        position.buyLots.removeFirst();
                    }
                }

                if (remaining > 0)
                {
                    int fallbackCost = position.quantity > 0 ? (int) Math.max(1L, position.totalCost / Math.max(1, position.quantity)) : Math.max(1, position.lastBuyPrice);
                    realized += (long) remaining * (sellPrice - fallbackCost);
                }

                gpMade += realized;
                itemsFlipped += 1;
                closedTrades += 1;
                if (realized >= 0)
                {
                    profitableTrades += 1;
                }
                else
                {
                    losingTrades += 1;
                }
                double prior = itemPriorityMemory.getOrDefault(itemId, 0.0);
                double adjustment = realized > 0 ? 0.08 : -0.10;
                itemPriorityMemory.put(itemId, Math.max(-0.8, Math.min(2.0, prior + adjustment)));

                long recomputedCost = 0;
                int recomputedQty = 0;
                for (BuyLot lot : position.buyLots)
                {
                    recomputedQty += lot.quantity;
                    recomputedCost += (long) lot.quantity * lot.unitPrice;
                }
                position.quantity = recomputedQty;
                position.totalCost = recomputedCost;
                if (position.quantity == 0)
                {
                    holdingsSince.remove(itemId);
                }
            }

            lastInventory.put(itemId, current);
        }
    }

    private boolean isItemBuyableForCurrentAccount(int itemId)
    {
        try
        {
            boolean inMembersWorld = WorldsAPI.inMembersWorld();
            Boolean membersMapped = wikiMembershipMap.get(itemId);
            Integer geLimit = wikiGeLimitMap.get(itemId);

            if (!inMembersWorld && Boolean.TRUE.equals(membersMapped))
            {
                return false;
            }

            if (geLimit != null && geLimit <= 0)
            {
                return false;
            }

            ItemComposition definition = client.getItemDefinition(itemId);
            if (definition != null)
            {
                if (!inMembersWorld && definition.isMembers())
                {
                    return false;
                }

                if (definition.isTradeable())
                {
                    return true;
                }
            }

            return geLimit != null ? geLimit > 0 : true;
        }
        catch (Exception ex)
        {
            Integer geLimit = wikiGeLimitMap.get(itemId);
            return geLimit == null || geLimit > 0;
        }
    }

    private int getFreeEligibleSlots()
    {
        int limit = WorldsAPI.inMembersWorld() ? 8 : 3;
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null)
        {
            return 0;
        }

        int free = 0;
        for (int i = 0; i < Math.min(limit, offers.length); i++)
        {
            GrandExchangeOffer offer = offers[i];
            if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
            {
                free++;
            }
        }
        return free;
    }

    private boolean hasActiveOffer(int itemId)
    {
        for (GrandExchangeOffer offer : GrandExchangeAPI.getOffers())
        {
            if (offer != null && offer.getItemId() == itemId && isActiveState(offer.getState()))
            {
                return true;
            }
        }
        return false;
    }

    private boolean isActiveState(GrandExchangeOfferState state)
    {
        return state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING;
    }

    private Set<Integer> getInventoryTradeableIds()
    {
        Set<Integer> ids = new HashSet<>();
        for (ItemEx item : InventoryAPI.getItems())
        {
            if (item == null || item.getId() <= 0)
            {
                continue;
            }
            ids.add(item.getId());
        }
        return ids;
    }

    private int inventoryAmount(int itemId)
    {
        int amount = 0;
        for (ItemEx item : InventoryAPI.getItems())
        {
            if (item != null && item.getId() == itemId)
            {
                amount += item.getQuantity();
            }
        }
        return amount;
    }

    private void updateSlotText()
    {
        int limit = WorldsAPI.inMembersWorld() ? 8 : 3;
        int used = Math.max(0, limit - getFreeEligibleSlots());
        slotText = used + "/" + limit;
    }

    private void humanPause()
    {
        humanPauseQuick();
    }

    private void humanPauseQuick()
    {
        Delays.wait(ThreadLocalRandom.current().nextInt(20, 85));
    }

    private int applyPercent(int base, double pct, boolean ceil)
    {
        double value = base * (1.0 + (pct / 100.0));
        return Math.max(1, (int) (ceil ? Math.ceil(value) : Math.floor(value)));
    }

    private String itemName(int itemId)
    {
        if (itemId <= 0)
        {
            return "None";
        }

        String wikiName = wikiItemNameMap.get(itemId);
        if (wikiName != null && !wikiName.trim().isEmpty())
        {
            return wikiName;
        }

        try
        {
            String name = client.getItemDefinition(itemId).getName();
            if (name != null && !name.trim().isEmpty())
            {
                return name;
            }
        }
        catch (Exception ignored)
        {
        }

        return "Item #" + itemId;
    }

    private JsonElement readJson(String url) throws Exception
    {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "VitaLite-MarketMentor/1.0");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)))
        {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                out.append(line);
            }
            return JsonParser.parseString(out.toString());
        }
    }

    private int getInt(JsonObject object, String key, int fallback)
    {
        if (object == null || !object.has(key) || object.get(key).isJsonNull())
        {
            return fallback;
        }

        try
        {
            return object.get(key).getAsInt();
        }
        catch (Exception ex)
        {
            return fallback;
        }
    }

    private Set<Integer> parseBlacklist(String csv)
    {
        if (csv == null || csv.trim().isEmpty())
        {
            return Collections.emptySet();
        }

        Set<Integer> ids = new HashSet<>();
        for (String token : csv.split(","))
        {
            try
            {
                ids.add(Integer.parseInt(token.trim()));
            }
            catch (Exception ignored)
            {
            }
        }
        return ids;
    }

    private void maybeSendDiscordSummary()
    {
        String webhook = config.discordWebhookUrl();
        if (webhook == null || webhook.trim().isEmpty())
        {
            return;
        }

        WebhookInterval interval = config.discordWebhookInterval();
        if (interval == null || interval == WebhookInterval.OFF)
        {
            return;
        }

        if (lastWebhookAt != Instant.EPOCH && Duration.between(lastWebhookAt, Instant.now()).compareTo(interval.getDuration()) < 0)
        {
            return;
        }

        try
        {
            String content = "Market Mentor Update\nP/L: " + getProfitText()
                    + "\nProfit/hr: " + getAverageGpPerHourText()
                    + "\nItems flipped: " + getItemsFlipped()
                    + "\nBest item: " + getBestSuggestionText();
            JsonObject payload = new JsonObject();
            payload.addProperty("content", content);

            HttpURLConnection connection = (HttpURLConnection) new URL(webhook).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.getOutputStream().write(payload.toString().getBytes(StandardCharsets.UTF_8));
            connection.getOutputStream().flush();
            connection.getOutputStream().close();
            connection.getInputStream().close();
            lastWebhookAt = Instant.now();
        }
        catch (Exception ex)
        {
            Logger.warn("[MarketMentor] webhook send failed: " + ex.getMessage());
        }
    }

    public String getRuntimeText()
    {
        if (startTime == null)
        {
            return "00:00:00";
        }

        Duration duration = Duration.between(startTime, Instant.now());
        return String.format("%02d:%02d:%02d", duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
    }

    public String getNextApiRefreshText()
    {
        if (lastMarketFetchAt == Instant.EPOCH)
        {
            return "now";
        }

        long remaining = MARKET_REFRESH_SECONDS - Duration.between(lastMarketFetchAt, Instant.now()).getSeconds();
        return remaining <= 0 ? "now" : remaining + "s";
    }

    public String getTradesText()
    {
        return closedTrades + " (W:" + profitableTrades + " L:" + losingTrades + ")";
    }

    public String getStatusText() { return statusText; }
    public String getSlotText() { return slotText; }
    public String getCoinsText() { return coinsText; }
    public String getProfitText() { return String.format("%,d", gpMade); }

    public String getAverageGpPerHourText()
    {
        if (startTime == null)
        {
            return "0";
        }

        long seconds = Math.max(1L, Duration.between(startTime, Instant.now()).getSeconds());
        long gpPerHour = (gpMade * 3600L) / seconds;
        return String.format("%,d", gpPerHour);
    }

    public int getItemsFlipped()
    {
        return (int) itemsFlipped;
    }

    public String getCurrentSuggestionText()
    {
        if (currentSuggestion == null)
        {
            return "None";
        }
        return itemName(currentSuggestion.itemId) + " (" + String.format("%.2f%%", currentSuggestion.netRoi * 100.0) + ")";
    }

    public String getBestSuggestionText()
    {
        if (bestSuggestion == null)
        {
            return "None";
        }
        return itemName(bestSuggestion.itemId) + " (" + String.format("%.2f%%", bestSuggestion.netRoi * 100.0) + ")";
    }
}
