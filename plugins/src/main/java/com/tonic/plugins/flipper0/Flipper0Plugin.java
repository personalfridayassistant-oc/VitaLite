package com.tonic.plugins.flipper0;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
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

@PluginDescriptor(
        name = "# Flipper0",
        description = "GE flipping assistant powered by external runelite suggestions API",
        tags = {"ge", "flipping", "merchanting", "automation"}
)
public class Flipper0Plugin extends VitaPlugin
{
    private static final String[] SUGGESTION_ENDPOINTS = {
            "http://192.168.1.27:3015/api/v1/suggestions?limit=100",
            "http://192.168.1.27/api/v1/suggestions/runelite?limit=25"
    };

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
    @Inject private Flipper0Overlay overlay;
    @Inject private OverlayManager overlayManager;
    @Inject private ClientToolbar clientToolbar;

    private NavigationButton navButton;

    private final Map<Integer, Instant> activeOfferSince = new HashMap<>();
    private final Set<Integer> runtimeBlacklist = new HashSet<>();
    private final List<Suggestion> suggestions = new ArrayList<>();

    private Instant startedAt = Instant.EPOCH;
    private Instant lastFetch = Instant.EPOCH;
    private int skipCount;
    private long realizedProfit;

    private Suggestion currentSuggestion;
    private Suggestion nextSuggestion;

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
        activeOfferSince.clear();
        startedAt = Instant.now();
        lastFetch = Instant.EPOCH;
        skipCount = 0;
        realizedProfit = 0;
        currentSuggestion = null;
        nextSuggestion = null;

        overlayManager.add(overlay);
        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/graph.png");
        navButton = NavigationButton.builder().tooltip("Flipper0").icon(icon).panel(panel).build();
        clientToolbar.addNavigation(navButton);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }
        statusText = "Stopped";
        refreshPanel();
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

        runtimeBlacklist.clear();
        runtimeBlacklist.addAll(parseCsv(config.blacklistItemIds()));
        updateSlotsText();
        coinsText = String.format("%,d", coinsOnHand());

        refreshSuggestionsIfNeeded();
        cancelStaleOffers();
        collectOffers();

        List<Suggestion> filtered = filteredSuggestions();
        currentSuggestion = pickCurrent(filtered);
        nextSuggestion = filtered.size() > 1 ? filtered.get(Math.min(skipCount + 1, filtered.size() - 1)) : null;

        if (currentSuggestion == null)
        {
            statusText = suggestions.isEmpty() ? "No API suggestions" : "No eligible suggestions";
            refreshPanel();
            Delays.tick(1);
            return;
        }

        if (!GrandExchangeAPI.isOpen())
        {
            statusText = "Opening GE";
            if (config.autoOpenGe())
            {
                openGrandExchange();
                Delays.wait(90);
            }
            refreshPanel();
            Delays.tick(1);
            return;
        }

        boolean sold = trySellHeldInventory(currentSuggestion);

        boolean bought = false;
        if (getFreeEligibleSlots() > 0)
        {
            bought = tryBuySuggestion(currentSuggestion);
        }
        else if (!sold)
        {
            statusText = "No free GE slots";
        }

        if (!sold && !bought && statusText.startsWith("Loaded"))
        {
            statusText = "Monitoring offers / awaiting fills";
        }

        refreshPanel();
        Delays.tick(Math.max(1, config.loopDelayTicks()));
    }

    private void refreshSuggestionsIfNeeded()
    {
        if (Instant.now().isBefore(lastFetch.plusSeconds(Math.max(3, config.refreshSeconds()))))
        {
            return;
        }

        List<Suggestion> fresh = fetchSuggestions();
        if (!fresh.isEmpty())
        {
            suggestions.clear();
            suggestions.addAll(fresh);
            statusText = "Loaded " + fresh.size() + " suggestions";
        }
        else if (suggestions.isEmpty())
        {
            statusText = "Suggestion API returned no items";
        }

        lastFetch = Instant.now();
    }

    private List<Suggestion> filteredSuggestions()
    {
        int spendable = Math.max(0, coinsOnHand() - config.coinReserve());
        boolean memberWorld = WorldsAPI.inMembersWorld();
        int maxTradeBudget = Math.max(1, config.maxGpPerTrade());
        int minVolume = Math.max(1, config.minVolume());

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
            if (s.minVolume < minVolume)
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
            if (!isTradeableForAccount(s.itemId, memberWorld))
            {
                continue;
            }

            int effectiveBudget = Math.min(spendable, maxTradeBudget);
            if (effectiveBudget < s.buyPrice)
            {
                continue;
            }

            filtered.add(s);
        }

        filtered.sort(Comparator
                .comparingLong((Suggestion s) -> s.ts).reversed()
                .thenComparingDouble((Suggestion s) -> s.score).reversed()
                .thenComparingInt((Suggestion s) -> s.minVolume).reversed());

        return filtered;
    }

    private Suggestion pickCurrent(List<Suggestion> filtered)
    {
        if (filtered.isEmpty())
        {
            return null;
        }

        int idx = Math.min(Math.max(0, skipCount), filtered.size() - 1);
        return filtered.get(idx);
    }

    private boolean trySellHeldInventory(Suggestion suggestion)
    {
        int qty = inventoryAmount(suggestion.itemId);
        if (qty <= 0 || hasActiveOffer(suggestion.itemId))
        {
            return false;
        }

        int price = Math.max(suggestion.buyPrice + 1, suggestion.sellPrice);
        clearNumericDialogue();
        boolean ok = GrandExchangeAPI.startSellOffer(suggestion.itemId, qty, price) != null;
        clearNumericDialogue();

        if (ok)
        {
            activeOfferSince.put(suggestion.itemId, Instant.now());
            long estimated = (long) qty * (price - suggestion.buyPrice);
            realizedProfit += Math.max(0, estimated);
            statusText = "Placed sell for " + suggestion.name;
            return true;
        }

        statusText = "Sell failed for " + suggestion.name;
        return false;
    }

    private boolean tryBuySuggestion(Suggestion suggestion)
    {
        if (hasActiveOffer(suggestion.itemId) || inventoryAmount(suggestion.itemId) > 0)
        {
            return false;
        }

        int spendable = Math.max(0, coinsOnHand() - config.coinReserve());
        int budget = Math.min(Math.max(1, config.maxGpPerTrade()), spendable);
        int qty = Math.min(Math.max(1, suggestion.geLimit), budget / Math.max(1, suggestion.buyPrice));
        if (qty <= 0)
        {
            statusText = "Not enough coins for " + suggestion.name;
            return false;
        }

        clearNumericDialogue();
        boolean ok = GrandExchangeAPI.startBuyOffer(suggestion.itemId, qty, suggestion.buyPrice) != null;
        clearNumericDialogue();

        if (ok)
        {
            activeOfferSince.put(suggestion.itemId, Instant.now());
            statusText = "Placed buy for " + suggestion.name + " x" + qty;
            return true;
        }

        statusText = "Buy failed for " + suggestion.name;
        return false;
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
        int timeout = Math.max(25, config.staleOfferSeconds());

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
            if (started != null && Duration.between(started, now).getSeconds() >= timeout)
            {
                GrandExchangeAPI.abortOffer(offer.getItemId());
                clearNumericDialogue();
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
            Delays.wait(20);
        }
    }

    private void clearNumericDialogue()
    {
        for (int i = 0; i < 2; i++)
        {
            ClientScriptAPI.closeNumericInputDialogue();
            Delays.wait(15);
        }
    }

    private void openGrandExchange()
    {
        NpcEx clerk = new NpcQuery().withNameContains("Grand Exchange Clerk").sortNearest().first();
        if (clerk != null)
        {
            NpcAPI.interact(clerk, "Exchange", "Talk-to", "Bank");
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

        for (String endpoint : SUGGESTION_ENDPOINTS)
        {
            List<Suggestion> parsed = fetchSuggestionsFromEndpoint(endpoint);
            if (!parsed.isEmpty())
            {
                out.addAll(parsed);
                break;
            }
        }

        dedupeByItemIdKeepBest(out);
        return out;
    }

    private List<Suggestion> fetchSuggestionsFromEndpoint(String endpoint)
    {
        List<Suggestion> out = new ArrayList<>();
        try
        {
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(6000);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300)
            {
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
            JsonArray arr = findSuggestionArray(root);
            if (arr == null)
            {
                return out;
            }

            for (JsonElement element : arr)
            {
                Suggestion suggestion = parseSuggestionElement(element);
                if (suggestion != null)
                {
                    out.add(suggestion);
                }
            }

            return out;
        }
        catch (Exception ignored)
        {
            return out;
        }
    }

    private Suggestion parseSuggestionElement(JsonElement element)
    {
        if (element == null || element.isJsonNull())
        {
            return null;
        }
        if (!element.isJsonObject())
        {
            return null;
        }

        JsonObject obj = element.getAsJsonObject();
        JsonObject itemObj = obj.has("item") && obj.get("item").isJsonObject() ? obj.getAsJsonObject("item") : obj;

        Suggestion s = new Suggestion();
        s.itemId = getInt(itemObj, "itemId", getInt(itemObj, "id", getInt(itemObj, "item_id", -1)));
        if (s.itemId <= 0)
        {
            return null;
        }

        ItemComposition def = client.getItemDefinition(s.itemId);
        s.name = getString(itemObj, "name", def != null ? def.getName() : ("Item " + s.itemId));

        int high = pickPositive(
                getInt(obj, "high", -1),
                getInt(obj, "highPrice", -1),
                getInt(obj, "sellPrice", -1),
                getInt(obj, "sell", -1),
                getInt(itemObj, "high", -1)
        );

        int low = pickPositive(
                getInt(obj, "low", -1),
                getInt(obj, "lowPrice", -1),
                getInt(obj, "buyPrice", -1),
                getInt(obj, "buy", -1),
                getInt(itemObj, "low", -1)
        );

        s.buyPrice = low;
        s.sellPrice = high;
        s.minVolume = pickPositive(
                getInt(obj, "minVolume", -1),
                getInt(obj, "volume", -1),
                getInt(obj, "volume_5m", -1),
                getInt(obj, "fiveMinuteVolume", -1),
                getInt(itemObj, "volume", -1),
                0
        );
        s.geLimit = Math.max(1, pickPositive(
                getInt(itemObj, "limit", -1),
                getInt(obj, "geLimit", -1),
                getInt(obj, "limit", -1),
                70
        ));

        boolean membersFromItem = getBoolean(itemObj, "members", def != null && def.isMembers());
        s.members = getBoolean(obj, "members", membersFromItem);

        s.score = pickPositiveDouble(
                getDouble(obj, "score", -1),
                getDouble(obj, "rankScore", -1),
                getDouble(obj, "opportunityScore", -1),
                0.0
        );

        s.ts = normalizeTimestamp(pickPositiveLong(
                getLong(obj, "timestamp", -1),
                getLong(obj, "updatedAt", -1),
                getLong(obj, "updated_at", -1),
                getLong(itemObj, "updatedAt", -1),
                Instant.now().getEpochSecond()
        ));

        if (s.buyPrice <= 0 || s.sellPrice <= s.buyPrice)
        {
            return null;
        }

        s.roiPct = ((s.sellPrice - s.buyPrice) * 100.0) / Math.max(1, s.buyPrice);
        if (s.score <= 0.0)
        {
            s.score = s.roiPct + (Math.log10(Math.max(1, s.minVolume)) * 1.8);
        }
        return s;
    }

    private JsonArray findSuggestionArray(JsonElement root)
    {
        if (root == null || root.isJsonNull())
        {
            return null;
        }
        if (root.isJsonArray())
        {
            return root.getAsJsonArray();
        }
        if (!root.isJsonObject())
        {
            return null;
        }

        JsonObject obj = root.getAsJsonObject();
        if (obj.has("suggestions") && obj.get("suggestions").isJsonArray())
        {
            return obj.getAsJsonArray("suggestions");
        }
        if (obj.has("data") && obj.get("data").isJsonArray())
        {
            return obj.getAsJsonArray("data");
        }
        if (obj.has("items") && obj.get("items").isJsonArray())
        {
            return obj.getAsJsonArray("items");
        }
        if (obj.has("results") && obj.get("results").isJsonArray())
        {
            return obj.getAsJsonArray("results");
        }
        return null;
    }

    private void dedupeByItemIdKeepBest(List<Suggestion> list)
    {
        if (list.isEmpty())
        {
            return;
        }

        Map<Integer, Suggestion> best = new HashMap<>();
        for (Suggestion s : list)
        {
            Suggestion prior = best.get(s.itemId);
            if (prior == null || s.ts > prior.ts || s.score > prior.score)
            {
                best.put(s.itemId, s);
            }
        }

        list.clear();
        list.addAll(best.values());
    }

    private int pickPositive(int... values)
    {
        for (int value : values)
        {
            if (value > 0)
            {
                return value;
            }
        }
        return -1;
    }

    private double pickPositiveDouble(double... values)
    {
        for (double value : values)
        {
            if (value > 0)
            {
                return value;
            }
        }
        return 0.0;
    }

    private long pickPositiveLong(long... values)
    {
        for (long value : values)
        {
            if (value > 0)
            {
                return value;
            }
        }
        return Instant.now().getEpochSecond();
    }

    private long normalizeTimestamp(long value)
    {
        if (value > 10_000_000_000L)
        {
            return value / 1000L;
        }
        return value;
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

    private void refreshPanel()
    {
        panel.refresh(
                statusText,
                coinsText,
                slotsText,
                getGpPerHourText(),
                currentSuggestion,
                new ArrayList<>(filteredSuggestions()),
                new Flipper0Panel.Actions()
                {
                    @Override
                    public void skipCurrent()
                    {
                        skipCount++;
                    }

                    @Override
                    public void blacklistCurrent()
                    {
                        if (currentSuggestion != null)
                        {
                            runtimeBlacklist.add(currentSuggestion.itemId);
                            skipCount = 0;
                        }
                    }
                }
        );
    }

    public boolean shouldRenderOverlay()
    {
        return config.enabled() && client.getGameState() == GameState.LOGGED_IN;
    }

    public String getStatusText() { return statusText; }
    public String getSlotsText() { return slotsText; }
    public Suggestion getCurrentSuggestion() { return currentSuggestion; }
    public Suggestion getNextSuggestion() { return nextSuggestion; }

    public String getGpPerHourText()
    {
        long secs = Math.max(1, Duration.between(startedAt, Instant.now()).getSeconds());
        long gphr = (realizedProfit * 3600L) / secs;
        return String.format("%,d", gphr);
    }
}
