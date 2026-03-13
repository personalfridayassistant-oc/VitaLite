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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final String WIKI_5M = "https://prices.runescape.wiki/api/v1/osrs/5m";
    private static final String WIKI_MAPPING = "https://prices.runescape.wiki/api/v1/osrs/mapping";
    private static final double ESTIMATED_GE_TAX = 0.01;

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

    private Instant lastMappingRefresh = Instant.EPOCH;
    private Instant startTime;
    private long gpMade;
    private long itemsFlipped;
    private int lastTradedItemId;
    private String statusText = "Idle";
    private String slotText = "0/0";
    private String coinsText = "0";
    private Opportunity currentSuggestion;
    private Opportunity bestSuggestion;

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
        lastTradedItemId = -1;
        statusText = "Starting";
        currentSuggestion = null;
        bestSuggestion = null;
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
        updatePnlTracking();
        updateSlotText();
        coinsText = String.format("%,d", inventoryAmount(ItemID.COINS));
        ClientScriptAPI.closeNumericInputDialogue();

        if (!GrandExchangeAPI.isOpen())
        {
            statusText = "Opening GE";
            if (config.autoOpenGe())
            {
                openGrandExchange();
                humanPause();
            }
            refreshPanel();
            Delays.tick(Math.max(1, config.loopDelayTicks()));
            return;
        }

        if (GrandExchangeAPI.canCollect())
        {
            GrandExchangeAPI.collectAll();
            humanPause();
        }

        pruneAndCancelStaleOffers();

        List<MarketSnapshot> snapshots = fetchSnapshots();
        List<Opportunity> opportunities = buildOpportunities(snapshots, false);
        if (opportunities.isEmpty())
        {
            opportunities = buildOpportunities(snapshots, true); // failsafe relaxed strategy
            if (!opportunities.isEmpty())
            {
                statusText = "Using relaxed failsafe strategy";
            }
        }

        opportunities.sort(Comparator.comparingDouble((Opportunity o) -> o.score).reversed());
        updatePanelOffers(opportunities);

        if (opportunities.isEmpty())
        {
            statusText = "No viable opportunities";
            currentSuggestion = null;
            refreshPanel();
            Delays.tick(Math.max(1, config.loopDelayTicks()));
            return;
        }

        currentSuggestion = opportunities.get(0);
        if (bestSuggestion == null || currentSuggestion.netRoi > bestSuggestion.netRoi)
        {
            bestSuggestion = currentSuggestion;
        }

        trackTopItems(opportunities);

        int actions = executeTrades(opportunities);
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

        refreshPanel();
        ClientScriptAPI.closeNumericInputDialogue();
        Delays.tick(Math.max(1, config.loopDelayTicks()));
    }

    private int executeTrades(List<Opportunity> opportunities)
    {
        int freeSlots = getFreeEligibleSlots();
        freeSlots = Math.max(0, freeSlots - Math.max(0, config.reservedSlots()));
        if (freeSlots <= 0)
        {
            statusText = "No free slots (after reserve)";
            return 0;
        }

        int actions = 0;
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
                humanPause();
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
            if (buyList.size() >= Math.max(1, config.maxItemsPerCycle()))
            {
                break;
            }
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
                continue;
            }

            if (submitBuy(opportunity, qty))
            {
                actions++;
                freeSlots--;
                lastTradedItemId = opportunity.itemId;
                coins = Math.max(0, coins - (qty * opportunity.buyPrice));
                activeOfferSince.put(opportunity.itemId, Instant.now());
                statusText = "Buying " + itemName(opportunity.itemId);
                humanPause();
            }
        }

        return actions;
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
            if (!isItemBuyableForCurrentAccount(snap.itemId) || snap.low <= 0 || snap.high <= snap.low)
            {
                continue;
            }

            if (snap.minVolume() < minVolume)
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

            double netPerItem = (sellPrice * (1.0 - ESTIMATED_GE_TAX)) - buyPrice;
            double netRoi = netPerItem / Math.max(1.0, buyPrice);
            if (netRoi < minNetRoi)
            {
                continue;
            }

            int volume = snap.minVolume();
            int spread = Math.max(1, snap.high - snap.low);
            int targetQuantity = Math.max(1, Math.min(volume / 2, Math.max(1, config.maxGpPerTrade()) / Math.max(1, buyPrice)));
            double score = (netPerItem * Math.log10(Math.max(10, volume))) + (spread * 0.01);
            opportunities.add(new Opportunity(snap.itemId, buyPrice, sellPrice, panicSell, targetQuantity, spread, volume, netRoi, score));
        }

        return opportunities;
    }

    private List<MarketSnapshot> fetchSnapshots()
    {
        List<MarketSnapshot> snapshots = new ArrayList<>();
        try
        {
            JsonObject latestData = readJson(WIKI_LATEST).getAsJsonObject().getAsJsonObject("data");
            JsonObject fiveData = readJson(WIKI_5M).getAsJsonObject().getAsJsonObject("data");
            if (latestData == null || fiveData == null)
            {
                return snapshots;
            }

            List<Integer> candidateIds = selectCandidateIds(fiveData, Math.max(250, config.maxUniverseItems()));
            for (Integer itemId : candidateIds)
            {
                JsonObject latest = latestData.getAsJsonObject(String.valueOf(itemId));
                JsonObject five = fiveData.getAsJsonObject(String.valueOf(itemId));
                if (latest == null || five == null)
                {
                    continue;
                }

                int high = getInt(latest, "high", 0);
                int low = getInt(latest, "low", 0);
                if (high <= 0 || low <= 0)
                {
                    continue;
                }

                snapshots.add(new MarketSnapshot(
                        itemId,
                        high,
                        low,
                        getInt(latest, "highTime", 0),
                        getInt(latest, "lowTime", 0),
                        getInt(five, "highPriceVolume", 0),
                        getInt(five, "lowPriceVolume", 0)
                ));
            }
        }
        catch (Exception ex)
        {
            Logger.warn("[MarketMentor] market fetch failed: " + ex.getMessage());
        }

        return snapshots;
    }

    private List<Integer> selectCandidateIds(JsonObject fiveMinuteData, int maxItems)
    {
        List<MarketSnapshot> ranked = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : fiveMinuteData.entrySet())
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

            JsonObject five = entry.getValue().getAsJsonObject();
            int highVolume = getInt(five, "highPriceVolume", 0);
            int lowVolume = getInt(five, "lowPriceVolume", 0);
            if (Math.min(highVolume, lowVolume) <= 0)
            {
                continue;
            }

            ranked.add(new MarketSnapshot(itemId, 1, 1, 0, 0, highVolume, lowVolume));
        }

        ranked.sort(Comparator.comparingInt(MarketSnapshot::minVolume).reversed());

        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < Math.min(maxItems, ranked.size()); i++)
        {
            ids.add(ranked.get(i).itemId);
        }
        return ids;
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
            for (JsonElement element : mappingArray)
            {
                JsonObject obj = element.getAsJsonObject();
                int id = getInt(obj, "id", -1);
                if (id <= 0)
                {
                    continue;
                }
                boolean members = obj.has("members") && !obj.get("members").isJsonNull() && obj.get("members").getAsBoolean();
                wikiMembershipMap.put(id, members);
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
        Position position = positions.computeIfAbsent(opportunity.itemId, k -> new Position());
        position.lastBuyPrice = opportunity.buyPrice;
        ClientScriptAPI.closeNumericInputDialogue();
        boolean ok = GrandExchangeAPI.startBuyOffer(opportunity.itemId, quantity, opportunity.buyPrice) != null;
        ClientScriptAPI.closeNumericInputDialogue();
        return ok;
    }

    private boolean submitSell(Opportunity opportunity, int inventoryAmount)
    {
        holdingsSince.putIfAbsent(opportunity.itemId, Instant.now());
        boolean timedOut = Duration.between(holdingsSince.get(opportunity.itemId), Instant.now()).getSeconds() >= Math.max(30, config.holdingTimeoutSeconds());
        int sellPrice = timedOut ? opportunity.panicSellPrice : opportunity.normalSellPrice;
        int quantity = Math.min(inventoryAmount, opportunity.targetQuantity);
        if (quantity <= 0)
        {
            return false;
        }

        Position position = positions.computeIfAbsent(opportunity.itemId, k -> new Position());
        position.lastSellPrice = sellPrice;
        ClientScriptAPI.closeNumericInputDialogue();
        boolean ok = GrandExchangeAPI.startSellOffer(opportunity.itemId, quantity, sellPrice) != null;
        ClientScriptAPI.closeNumericInputDialogue();
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
                ClientScriptAPI.closeNumericInputDialogue();
                humanPause();
                activeOfferSince.put(offer.getItemId(), now);
            }
        }
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
                int price = Math.max(1, position.lastBuyPrice);
                position.quantity += delta;
                position.totalCost += (long) delta * price;
                holdingsSince.putIfAbsent(itemId, Instant.now());
            }
            else if (delta < 0)
            {
                int sold = -delta;
                int avgCost = position.quantity > 0 ? (int) Math.max(1L, position.totalCost / position.quantity) : Math.max(1, position.lastBuyPrice);
                int sellPrice = Math.max(1, position.lastSellPrice);
                gpMade += (long) sold * (sellPrice - avgCost);
                itemsFlipped += sold;

                position.quantity = Math.max(0, position.quantity - sold);
                position.totalCost = Math.max(0L, position.totalCost - (long) sold * avgCost);
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
            boolean membersItem = wikiMembershipMap.getOrDefault(itemId, false);
            if (!WorldsAPI.inMembersWorld() && membersItem)
            {
                return false;
            }

            ItemComposition definition = client.getItemDefinition(itemId);
            if (definition == null || !definition.isTradeable())
            {
                return false;
            }

            if (!WorldsAPI.inMembersWorld() && definition.isMembers())
            {
                return false;
            }

            return true;
        }
        catch (Exception ex)
        {
            return false;
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
        Delays.wait(ThreadLocalRandom.current().nextInt(80, 320));
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

        try
        {
            String name = client.getItemDefinition(itemId).getName();
            return name == null ? String.valueOf(itemId) : name;
        }
        catch (Exception ex)
        {
            return String.valueOf(itemId);
        }
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

    public String getRuntimeText()
    {
        if (startTime == null)
        {
            return "00:00:00";
        }

        Duration duration = Duration.between(startTime, Instant.now());
        return String.format("%02d:%02d:%02d", duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
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
