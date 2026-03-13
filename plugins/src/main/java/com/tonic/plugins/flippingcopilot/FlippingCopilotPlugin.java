package com.tonic.plugins.flippingcopilot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import com.tonic.Logger;
import com.tonic.api.entities.NpcAPI;
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
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
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

@PluginDescriptor(
        name = "# Flipping Copilot Auto",
        description = "Automatic smart GE flips using live market data and VitaLite APIs",
        tags = {"grand exchange", "flipping", "automation", "trading"}
)
public class FlippingCopilotPlugin extends VitaPlugin
{
    private static final String WIKI_LATEST = "https://prices.runescape.wiki/api/v1/osrs/latest";
    private static final String WIKI_5M = "https://prices.runescape.wiki/api/v1/osrs/5m";

    private static final class MarketSnapshot
    {
        private final int high;
        private final int low;
        private final int highTime;
        private final int lowTime;
        private final int highVolume;
        private final int lowVolume;

        private MarketSnapshot(int high, int low, int highTime, int lowTime, int highVolume, int lowVolume)
        {
            this.high = high;
            this.low = low;
            this.highTime = highTime;
            this.lowTime = lowTime;
            this.highVolume = highVolume;
            this.lowVolume = lowVolume;
        }
    }

    private static final class Opportunity
    {
        private final int itemId;
        private final int targetQuantity;
        private final int buyPrice;
        private final int normalSellPrice;
        private final int panicSellPrice;
        private final double roi;
        private final double score;

        private Opportunity(int itemId, int targetQuantity, int buyPrice, int normalSellPrice, int panicSellPrice, double roi, double score)
        {
            this.itemId = itemId;
            this.targetQuantity = targetQuantity;
            this.buyPrice = buyPrice;
            this.normalSellPrice = normalSellPrice;
            this.panicSellPrice = panicSellPrice;
            this.roi = roi;
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

    @Inject
    private FlippingCopilotConfig config;

    @Inject
    private Client client;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private FlippingCopilotOverlay overlay;

    private final Map<Integer, Instant> activeOfferSince = new HashMap<>();
    private final Map<Integer, Instant> holdingsSince = new HashMap<>();
    private final Map<Integer, Integer> lastInventory = new HashMap<>();
    private final Map<Integer, Position> positions = new HashMap<>();

    private Instant startTime;
    private long gpMade;
    private String statusText = "Idle";
    private Opportunity currentProposed;
    private Opportunity bestSeen;

    @Provides
    FlippingCopilotConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(FlippingCopilotConfig.class);
    }

    @Override
    protected void startUp()
    {
        startTime = Instant.now();
        gpMade = 0;
        statusText = "Starting";
        currentProposed = null;
        bestSeen = null;
        overlayManager.add(overlay);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        statusText = "Stopped";
    }

    @Override
    public void loop() throws Exception
    {
        if (!config.enabled() || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            statusText = "Disabled / not logged in";
            return;
        }

        Set<Integer> candidates = parseCandidateIds(config.candidateItemIds());
        updatePnlTracking(candidates);

        if (!GrandExchangeAPI.isOpen())
        {
            statusText = "Opening GE";
            if (config.autoOpenGe())
            {
                openGrandExchange();
            }
            Delays.tick(Math.max(1, config.loopDelayTicks()));
            return;
        }

        if (GrandExchangeAPI.canCollect())
        {
            GrandExchangeAPI.collectAll();
            Delays.tick(1);
        }

        pruneAndCancelStaleOffers();

        if (candidates.isEmpty())
        {
            statusText = "No candidate ids";
            Delays.tick(Math.max(1, config.loopDelayTicks()));
            return;
        }

        Map<Integer, MarketSnapshot> snapshots = fetchSnapshots(candidates);
        if (snapshots.isEmpty())
        {
            statusText = "No market data";
            Delays.tick(Math.max(1, config.loopDelayTicks()));
            return;
        }

        List<Opportunity> opportunities = buildOpportunities(candidates, snapshots);
        opportunities.sort(Comparator.comparingDouble((Opportunity o) -> o.score).reversed());

        if (opportunities.isEmpty())
        {
            statusText = "No viable opportunities";
            currentProposed = null;
            Delays.tick(Math.max(1, config.loopDelayTicks()));
            return;
        }

        currentProposed = opportunities.get(0);
        if (bestSeen == null || currentProposed.roi > bestSeen.roi)
        {
            bestSeen = currentProposed;
        }

        if (GrandExchangeAPI.freeSlot() == -1)
        {
            statusText = "Waiting for free GE slot";
            Delays.tick(Math.max(1, config.loopDelayTicks()));
            return;
        }

        int take = Math.min(Math.max(1, config.targetItemsPerCycle()), opportunities.size());
        for (int i = 0; i < take; i++)
        {
            Opportunity opportunity = opportunities.get(i);
            if (hasActiveOffer(opportunity.itemId))
            {
                continue;
            }

            if (submitOpportunity(opportunity))
            {
                activeOfferSince.put(opportunity.itemId, Instant.now());
                statusText = "Submitted " + itemName(opportunity.itemId);
                Delays.tick(Math.max(1, config.loopDelayTicks()));
                return;
            }
        }

        statusText = "No actionable trade";
        Delays.tick(Math.max(1, config.loopDelayTicks()));
    }

    private void openGrandExchange()
    {
        NpcEx clerk = new NpcQuery().withNameContains("Grand Exchange Clerk").sortNearest().first();
        if (clerk != null)
        {
            NpcAPI.interact(clerk, "Exchange", "Talk-to", "Bank");
        }
    }

    private void pruneAndCancelStaleOffers()
    {
        int timeoutSeconds = Math.max(20, config.staleOfferSeconds());
        Instant now = Instant.now();

        for (GrandExchangeOffer offer : GrandExchangeAPI.getOffers())
        {
            if (offer == null || offer.getItemId() <= 0)
            {
                continue;
            }

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
                activeOfferSince.put(offer.getItemId(), now);
            }
        }
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

    private Set<Integer> parseCandidateIds(String csv)
    {
        Set<Integer> ids = new HashSet<>();
        if (csv == null || csv.trim().isEmpty())
        {
            return ids;
        }

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

    private Map<Integer, MarketSnapshot> fetchSnapshots(Set<Integer> itemIds)
    {
        Map<Integer, MarketSnapshot> snapshots = new HashMap<>();
        try
        {
            JsonObject latestRoot = readJson(WIKI_LATEST).getAsJsonObject();
            JsonObject latestData = latestRoot.getAsJsonObject("data");
            JsonObject fiveRoot = readJson(WIKI_5M).getAsJsonObject();
            JsonObject fiveData = fiveRoot.getAsJsonObject("data");

            for (int id : itemIds)
            {
                JsonObject latest = latestData == null ? null : latestData.getAsJsonObject(String.valueOf(id));
                JsonObject five = fiveData == null ? null : fiveData.getAsJsonObject(String.valueOf(id));
                if (latest == null || five == null)
                {
                    continue;
                }

                int high = getInt(latest, "high", 0);
                int low = getInt(latest, "low", 0);
                if (high <= 0 || low <= 0 || high <= low)
                {
                    continue;
                }

                snapshots.put(id, new MarketSnapshot(
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
            Logger.warn("[FlippingCopilot] failed to fetch market snapshots: " + ex.getMessage());
        }
        return snapshots;
    }

    private List<Opportunity> buildOpportunities(Set<Integer> candidates, Map<Integer, MarketSnapshot> snapshots)
    {
        List<Opportunity> opportunities = new ArrayList<>();
        double minRoi = Math.max(0.1, config.minRoiPercent()) / 100.0;
        int minVolume = Math.max(1, config.minFiveMinVolume());
        int maxGpPerTrade = Math.max(10_000, config.maxGpPerTrade());

        for (int itemId : candidates)
        {
            if (isMembersOnlyItemOnFreeWorld(itemId))
            {
                continue;
            }

            MarketSnapshot snap = snapshots.get(itemId);
            if (snap == null)
            {
                continue;
            }

            int volume = Math.min(snap.highVolume, snap.lowVolume);
            if (volume < minVolume)
            {
                continue;
            }

            double roi = (double) (snap.high - snap.low) / (double) snap.low;
            if (roi < minRoi)
            {
                continue;
            }

            int buyPrice = applyPercent(snap.low, config.buyPriceBumpPercent(), true);
            int normalSellPrice = Math.max(buyPrice + 1, applyPercent(snap.high, -Math.abs(config.sellPriceUndercutPercent()), false));
            int panicSellPrice = Math.max(1, applyPercent(snap.low, -Math.abs(config.panicSellDiscountPercent()), false));
            int targetQuantity = Math.max(1, Math.min(volume / 2, maxGpPerTrade / Math.max(1, buyPrice)));

            if (targetQuantity <= 0)
            {
                continue;
            }

            long agePenalty = Math.max(0, (System.currentTimeMillis() / 1000L) - Math.min(snap.highTime, snap.lowTime));
            double freshness = 1.0 / Math.max(1.0, agePenalty / 300.0);
            double score = (normalSellPrice - buyPrice) * Math.log10(Math.max(10, volume)) * freshness;

            opportunities.add(new Opportunity(itemId, targetQuantity, buyPrice, normalSellPrice, panicSellPrice, roi, score));
        }

        return opportunities;
    }

    private boolean submitOpportunity(Opportunity opportunity)
    {
        int inventoryAmount = inventoryAmount(opportunity.itemId);

        if (inventoryAmount < opportunity.targetQuantity)
        {
            int remaining = opportunity.targetQuantity - inventoryAmount;
            int availableCoins = inventoryAmount(ItemID.COINS);
            int affordableQuantity = Math.min(remaining, availableCoins / Math.max(1, opportunity.buyPrice));
            if (affordableQuantity <= 0)
            {
                statusText = "Insufficient coins for " + itemName(opportunity.itemId);
                return false;
            }

            Position position = positions.computeIfAbsent(opportunity.itemId, k -> new Position());
            position.lastBuyPrice = opportunity.buyPrice;

            return GrandExchangeAPI.startBuyOffer(opportunity.itemId, affordableQuantity, opportunity.buyPrice) != null;
        }

        holdingsSince.putIfAbsent(opportunity.itemId, Instant.now());
        boolean timedOut = Duration.between(holdingsSince.get(opportunity.itemId), Instant.now()).getSeconds() >= Math.max(30, config.sellTimeoutSeconds());
        int sellPrice = timedOut ? opportunity.panicSellPrice : opportunity.normalSellPrice;

        int quantityToSell = Math.min(inventoryAmount, opportunity.targetQuantity);
        if (quantityToSell <= 0)
        {
            return false;
        }

        Position position = positions.computeIfAbsent(opportunity.itemId, k -> new Position());
        position.lastSellPrice = sellPrice;

        return GrandExchangeAPI.startSellOffer(opportunity.itemId, quantityToSell, sellPrice) != null;
    }

    private boolean isMembersOnlyItemOnFreeWorld(int itemId)
    {
        if (WorldsAPI.inMembersWorld())
        {
            return false;
        }

        String name = itemName(itemId);
        return name.endsWith("(Members)") || name.contains("Members");
    }

    private void updatePnlTracking(Set<Integer> candidates)
    {
        for (int itemId : candidates)
        {
            int current = inventoryAmount(itemId);
            int previous = lastInventory.getOrDefault(itemId, current);
            int delta = current - previous;

            Position p = positions.computeIfAbsent(itemId, k -> new Position());
            if (delta > 0)
            {
                int price = Math.max(1, p.lastBuyPrice);
                p.quantity += delta;
                p.totalCost += (long) delta * price;
                holdingsSince.putIfAbsent(itemId, Instant.now());
            }
            else if (delta < 0)
            {
                int sold = -delta;
                int avgCost = p.quantity > 0 ? (int) Math.max(1L, p.totalCost / p.quantity) : Math.max(1, p.lastBuyPrice);
                int sellPrice = Math.max(1, p.lastSellPrice);
                gpMade += (long) sold * (sellPrice - avgCost);

                p.quantity = Math.max(0, p.quantity - sold);
                p.totalCost = Math.max(0L, p.totalCost - (long) sold * avgCost);

                if (p.quantity == 0)
                {
                    holdingsSince.remove(itemId);
                }
            }

            lastInventory.put(itemId, current);
        }
    }

    private int applyPercent(int base, double pct, boolean ceil)
    {
        double value = base * (1.0 + (pct / 100.0));
        return Math.max(1, (int) (ceil ? Math.ceil(value) : Math.floor(value)));
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

    private String itemName(int itemId)
    {
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
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("User-Agent", "VitaLite-FlippingCopilot/1.0");

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

    private int getInt(JsonObject object, String key, int defaultValue)
    {
        if (object == null || !object.has(key) || object.get(key).isJsonNull())
        {
            return defaultValue;
        }

        try
        {
            return object.get(key).getAsInt();
        }
        catch (Exception ex)
        {
            return defaultValue;
        }
    }

    public long getGpMade()
    {
        return gpMade;
    }

    public String getRuntimeText()
    {
        if (startTime == null)
        {
            return "00:00:00";
        }

        Duration d = Duration.between(startTime, Instant.now());
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    public String getProposedItemText()
    {
        if (currentProposed == null)
        {
            return "None";
        }

        return itemName(currentProposed.itemId) + " (" + String.format("%.2f%%", currentProposed.roi * 100.0) + ")";
    }

    public String getBestReturnItemText()
    {
        if (bestSeen == null)
        {
            return "None";
        }

        return itemName(bestSeen.itemId) + " (" + String.format("%.2f%%", bestSeen.roi * 100.0) + ")";
    }

    public String getStatusText()
    {
        return statusText;
    }
}
