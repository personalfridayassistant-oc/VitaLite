package com.tonic.plugins.flipper0;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import com.tonic.api.game.WorldsAPI;
import com.tonic.api.widgets.GrandExchangeAPI;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.data.wrappers.ItemEx;
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
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@PluginDescriptor(
        name = "# Flipper0",
        description = "GE flipping assistant powered by external runelite suggestions API",
        tags = {"ge", "flipping", "merchanting", "automation"}
)
public class Flipper0Plugin extends VitaPlugin
{
    private static final String SUGGESTIONS_ENDPOINT = "http://192.168.1.27/api/v1/suggestions/runelite?limit=25";

    static class Suggestion
    {
        int itemId;
        String name;
        int buyPrice;
        int sellPrice;
        int minVolume;
        int geLimit;
        boolean members;
        double roiPct;
        double score;
        long ts;
    }

    @Inject private Flipper0Config config;
    @Inject private Client client;
    @Inject private Flipper0Panel panel;
    @Inject private ClientToolbar clientToolbar;

    private NavigationButton navButton;

    private final Map<Integer, Instant> activeOfferSince = new HashMap<>();
    private final Set<Integer> runtimeBlacklist = new HashSet<>();
    private final List<Suggestion> suggestions = new ArrayList<>();

    private Instant lastFetch = Instant.EPOCH;
    private int skipCount;
    private int lastSuggestedItem = -1;
    private String statusText = "Idle";
    private String slotsText = "0/0";
    private String coinsText = "0";

    @Provides
    Flipper0Config provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(Flipper0Config.class);
    }

    @Override
    protected void startUp()
    {
        runtimeBlacklist.clear();
        suggestions.clear();
        lastFetch = Instant.EPOCH;
        skipCount = 0;
        lastSuggestedItem = -1;

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/graph.png");
        navButton = NavigationButton.builder().tooltip("Flipper0").icon(icon).panel(panel).build();
        clientToolbar.addNavigation(navButton);
    }

    @Override
    protected void shutDown()
    {
        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }
        statusText = "Stopped";
        refreshPanel(null);
    }

    @Override
    public void loop() throws Exception
    {
        if (!config.enabled() || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            statusText = "Disabled / not logged in";
            refreshPanel(null);
            return;
        }

        runtimeBlacklist.addAll(parseCsv(config.blacklistItemIds()));
        updateSlotsText();
        coinsText = String.valueOf(coinsOnHand());

        if (Instant.now().isAfter(lastFetch.plusSeconds(15)))
        {
            suggestions.clear();
            suggestions.addAll(fetchSuggestions());
            lastFetch = Instant.now();
        }

        cancelStaleOffers();
        collectOffers();

        Suggestion best = selectSuggestion();
        refreshPanel(best);
        if (best == null)
        {
            statusText = "No valid suggestions";
            return;
        }

        int freeSlots = getFreeEligibleSlots();
        if (freeSlots <= 0)
        {
            statusText = "No free GE slots";
            return;
        }

        if (!GrandExchangeAPI.isOpen())
        {
            statusText = "Open GE to trade";
            return;
        }

        processSellFirst(best);
        placeBuyIfPossible(best);
    }

    private void processSellFirst(Suggestion best)
    {
        int qty = inventoryAmount(best.itemId);
        if (qty <= 0 || hasActiveOffer(best.itemId))
        {
            return;
        }

        int sellPrice = Math.max(best.buyPrice + 1, best.sellPrice);
        boolean ok = GrandExchangeAPI.startSellOffer(best.itemId, qty, sellPrice) != null;
        statusText = ok ? "Placed sell for " + best.name : "Sell failed for " + best.name;
        if (ok)
        {
            activeOfferSince.put(best.itemId, Instant.now());
        }
    }

    private void placeBuyIfPossible(Suggestion best)
    {
        if (hasActiveOffer(best.itemId) || inventoryAmount(best.itemId) > 0)
        {
            return;
        }

        int spendable = Math.max(0, coinsOnHand() - config.coinReserve());
        int budget = Math.min(config.maxGpPerTrade(), spendable);
        int qty = Math.min(best.geLimit, budget / Math.max(1, best.buyPrice));
        if (qty <= 0)
        {
            statusText = "Not enough coins for " + best.name;
            return;
        }

        boolean ok = GrandExchangeAPI.startBuyOffer(best.itemId, qty, best.buyPrice) != null;
        statusText = ok ? "Placed buy for " + best.name : "Buy failed for " + best.name;
        if (ok)
        {
            activeOfferSince.put(best.itemId, Instant.now());
            lastSuggestedItem = best.itemId;
        }
    }

    private Suggestion selectSuggestion()
    {
        if (suggestions.isEmpty())
        {
            return null;
        }

        int spendable = Math.max(0, coinsOnHand() - config.coinReserve());
        boolean memberWorld = WorldsAPI.inMembersWorld();

        List<Suggestion> filtered = new ArrayList<>();
        for (Suggestion s : suggestions)
        {
            if (s == null || s.itemId <= 0 || s.buyPrice <= 0 || s.sellPrice <= s.buyPrice)
            {
                continue;
            }
            if (runtimeBlacklist.contains(s.itemId))
            {
                continue;
            }
            if (s.minVolume < config.minVolume())
            {
                continue;
            }
            if (s.ts > 0 && Instant.now().getEpochSecond() - s.ts > config.maxDataAgeSeconds())
            {
                continue;
            }
            if (!memberWorld && s.members)
            {
                continue;
            }
            if (s.buyPrice > spendable || s.buyPrice > config.maxGpPerTrade())
            {
                continue;
            }
            if (!isTradeableForAccount(s.itemId, memberWorld))
            {
                continue;
            }
            filtered.add(s);
        }

        filtered.sort(Comparator.comparingDouble((Suggestion s) -> s.score).reversed()
                .thenComparingInt(s -> s.minVolume)
                .thenComparingDouble(s -> s.roiPct));

        if (filtered.isEmpty())
        {
            return null;
        }

        int idx = Math.min(skipCount, filtered.size() - 1);
        return filtered.get(idx);
    }

    private boolean isTradeableForAccount(int itemId, boolean memberWorld)
    {
        ItemComposition def = client.getItemDefinition(itemId);
        if (def == null)
        {
            return true;
        }
        if (!memberWorld && def.isMembers())
        {
            return false;
        }
        return def.isTradeable();
    }

    private void cancelStaleOffers()
    {
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
            Instant started = activeOfferSince.get(offer.getItemId());
            if (started != null && now.isAfter(started.plusSeconds(config.staleOfferSeconds())))
            {
                GrandExchangeAPI.abortOffer(offer.getItemId());
                activeOfferSince.put(offer.getItemId(), now);
                statusText = "Cancelled stale offer " + offer.getItemId();
            }
        }
    }

    private void collectOffers()
    {
        if (!GrandExchangeAPI.canCollect())
        {
            return;
        }
        for (int i = 0; i < 2 && GrandExchangeAPI.canCollect(); i++)
        {
            GrandExchangeAPI.collectAll();
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

    private int coinsOnHand()
    {
        return inventoryAmount(ItemID.COINS);
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

    private void updateSlotsText()
    {
        int limit = WorldsAPI.inMembersWorld() ? 8 : 3;
        int used = Math.max(0, limit - getFreeEligibleSlots());
        slotsText = used + "/" + limit;
    }

    private List<Suggestion> fetchSuggestions()
    {
        List<Suggestion> out = new ArrayList<>();
        try
        {
            URL url = new URL(SUGGESTIONS_ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(4500);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300)
            {
                statusText = "API error: " + code;
                return out;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = br.readLine()) != null)
                {
                    sb.append(line);
                }
            }

            JsonElement root = JsonParser.parseString(sb.toString());
            JsonArray arr = toArray(root);
            if (arr == null)
            {
                statusText = "API payload invalid";
                return out;
            }

            for (JsonElement element : arr)
            {
                if (!element.isJsonObject())
                {
                    continue;
                }
                Suggestion s = parseSuggestion(element.getAsJsonObject());
                if (s != null)
                {
                    out.add(s);
                }
            }
            statusText = "Loaded " + out.size() + " suggestions";
            return out;
        }
        catch (Exception ex)
        {
            statusText = "API unavailable";
            return out;
        }
    }

    private JsonArray toArray(JsonElement root)
    {
        if (root == null || root.isJsonNull())
        {
            return null;
        }
        if (root.isJsonArray())
        {
            return root.getAsJsonArray();
        }
        if (root.isJsonObject())
        {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("suggestions") && obj.get("suggestions").isJsonArray())
            {
                return obj.getAsJsonArray("suggestions");
            }
            if (obj.has("data") && obj.get("data").isJsonArray())
            {
                return obj.getAsJsonArray("data");
            }
        }
        return null;
    }

    private Suggestion parseSuggestion(JsonObject obj)
    {
        Suggestion s = new Suggestion();
        s.itemId = getInt(obj, "itemId", getInt(obj, "id", -1));
        if (s.itemId <= 0)
        {
            return null;
        }

        ItemComposition def = client.getItemDefinition(s.itemId);
        s.name = getString(obj, "name", def != null ? def.getName() : ("Item " + s.itemId));

        s.buyPrice = getInt(obj, "buyPrice", getInt(obj, "buy", getInt(obj, "low", -1)));
        s.sellPrice = getInt(obj, "sellPrice", getInt(obj, "sell", getInt(obj, "high", -1)));
        s.minVolume = getInt(obj, "minVolume", getInt(obj, "volume", 0));
        s.geLimit = Math.max(1, getInt(obj, "geLimit", 70));
        s.members = getBoolean(obj, "members", def != null && def.isMembers());
        s.score = getDouble(obj, "score", 0.0);
        s.ts = getLong(obj, "timestamp", getLong(obj, "ts", Instant.now().getEpochSecond()));

        if (s.buyPrice <= 0 || s.sellPrice <= s.buyPrice)
        {
            return null;
        }

        s.roiPct = ((s.sellPrice - s.buyPrice) * 100.0) / s.buyPrice;
        if (s.score == 0.0)
        {
            s.score = s.roiPct + (Math.log10(Math.max(1, s.minVolume)) * 2.0);
        }
        return s;
    }

    private int getInt(JsonObject obj, String key, int fallback)
    {
        try { return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private long getLong(JsonObject obj, String key, long fallback)
    {
        try { return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private double getDouble(JsonObject obj, String key, double fallback)
    {
        try { return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private boolean getBoolean(JsonObject obj, String key, boolean fallback)
    {
        try { return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private String getString(JsonObject obj, String key, String fallback)
    {
        try { return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private Set<Integer> parseCsv(String csv)
    {
        Set<Integer> set = new HashSet<>();
        if (csv == null || csv.trim().isEmpty())
        {
            return set;
        }

        for (String token : csv.split(","))
        {
            try
            {
                set.add(Integer.parseInt(token.trim()));
            }
            catch (Exception ignored)
            {
            }
        }
        return set;
    }

    private void refreshPanel(Suggestion current)
    {
        panel.refresh(statusText, coinsText, slotsText, current, suggestions, new Flipper0Panel.Actions()
        {
            @Override
            public void skipCurrent()
            {
                skipCount++;
            }

            @Override
            public void blacklistCurrent()
            {
                if (current != null)
                {
                    runtimeBlacklist.add(current.itemId);
                    if (current.itemId == lastSuggestedItem)
                    {
                        lastSuggestedItem = -1;
                    }
                }
            }
        });
    }
}
