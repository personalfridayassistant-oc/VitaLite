package com.tonic.plugins.flippingcopilot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import com.tonic.Logger;
import com.tonic.api.entities.NpcAPI;
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
        private final int quantity;
        private final int buyPrice;
        private final int normalSellPrice;
        private final int panicSellPrice;
        private final double score;

        private Opportunity(int itemId, int quantity, int buyPrice, int normalSellPrice, int panicSellPrice, double score)
        {
            this.itemId = itemId;
            this.quantity = quantity;
            this.buyPrice = buyPrice;
            this.normalSellPrice = normalSellPrice;
            this.panicSellPrice = panicSellPrice;
            this.score = score;
        }
    }

    @Inject
    private FlippingCopilotConfig config;

    @Inject
    private Client client;

    private final Map<Integer, Instant> activeOfferSince = new HashMap<>();
    private final Map<Integer, Instant> holdingsSince = new HashMap<>();

    @Provides
    FlippingCopilotConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(FlippingCopilotConfig.class);
    }

    @Override
    public void loop() throws Exception
    {
        if (!config.enabled() || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            return;
        }

        if (!GrandExchangeAPI.isOpen())
        {
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

        if (GrandExchangeAPI.freeSlot() == -1)
        {
            Delays.tick(Math.max(1, config.loopDelayTicks()));
            return;
        }

        Set<Integer> candidates = parseCandidateIds(config.candidateItemIds());
        if (candidates.isEmpty())
        {
            Delays.tick(Math.max(1, config.loopDelayTicks()));
            return;
        }

        Map<Integer, MarketSnapshot> snapshots = fetchSnapshots(candidates);
        if (snapshots.isEmpty())
        {
            Delays.tick(Math.max(1, config.loopDelayTicks()));
            return;
        }

        List<Opportunity> opportunities = buildOpportunities(candidates, snapshots);
        opportunities.sort(Comparator.comparingDouble((Opportunity o) -> o.score).reversed());

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
                Delays.tick(Math.max(1, config.loopDelayTicks()));
                return;
            }
        }

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
            int quantity = Math.max(1, Math.min(volume / 2, maxGpPerTrade / Math.max(1, buyPrice)));

            if (quantity <= 0)
            {
                continue;
            }

            long agePenalty = Math.max(0, (System.currentTimeMillis() / 1000L) - Math.min(snap.highTime, snap.lowTime));
            double freshness = 1.0 / Math.max(1.0, agePenalty / 300.0);
            double score = (snap.high - buyPrice) * Math.log10(Math.max(10, volume)) * freshness;

            opportunities.add(new Opportunity(itemId, quantity, buyPrice, normalSellPrice, panicSellPrice, score));
        }

        return opportunities;
    }

    private boolean submitOpportunity(Opportunity opportunity)
    {
        int inventoryAmount = inventoryAmount(opportunity.itemId);
        int coins = inventoryAmount(ItemID.COINS);

        if (inventoryAmount > 0)
        {
            holdingsSince.putIfAbsent(opportunity.itemId, Instant.now());
            boolean timedOut = Duration.between(holdingsSince.get(opportunity.itemId), Instant.now()).getSeconds() >= Math.max(30, config.sellTimeoutSeconds());
            int sellPrice = timedOut ? opportunity.panicSellPrice : opportunity.normalSellPrice;
            int amount = Math.min(inventoryAmount, opportunity.quantity);
            return GrandExchangeAPI.startSellOffer(opportunity.itemId, amount, sellPrice) != null;
        }

        holdingsSince.remove(opportunity.itemId);

        int affordable = coins / Math.max(1, opportunity.buyPrice);
        int amount = Math.min(opportunity.quantity, affordable);
        if (amount <= 0)
        {
            return false;
        }

        return GrandExchangeAPI.startBuyOffer(opportunity.itemId, amount, opportunity.buyPrice) != null;
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
}
