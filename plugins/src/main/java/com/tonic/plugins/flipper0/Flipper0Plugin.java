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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(Flipper0Plugin.class);

    private static final String[] SUGGESTION_ENDPOINTS = {
            "http://192.168.1.27:3015/api/v1/suggestions/runelite?limit=200",
            "http://192.168.1.27:3015/api/v1/suggestions?limit=200",
            "http://192.168.1.27/api/v1/suggestions/runelite?limit=200"
    };
    private static final String HEALTH_ENDPOINT = "http://192.168.1.27:3015/api/v1/health";
    private static final int MAX_REDIRECTS = 3;

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
    private Instant lastHealthCheck = Instant.EPOCH;
    private int skipCount;
    private long realizedProfit;

    private Suggestion currentSuggestion;
    private Suggestion nextSuggestion;

    private String statusText = "Idle";
    private String slotsText = "0/0";
    private String coinsText = "0";
    private String apiHealthText = "Unknown";
    private boolean apiHealthy;
    private String lastSuggestionDebug = "No fetch yet";
    private Instant nextBuyAttemptAt = Instant.EPOCH;

    private static class FilterStats
    {
        int accepted;
        int invalidPrice;
        int blacklisted;
        int lowVolume;
        int stale;
        int membersBlocked;
        int untradeable;
        int overBudget;
    }

    @Provides
    Flipper0Config provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(Flipper0Config.class);
    }

    @Override
    protected void startUp()
    {
        log.info("Flipper0 starting up");
        runtimeBlacklist.clear();
        suggestions.clear();
        activeOfferSince.clear();
        startedAt = Instant.now();
        lastFetch = Instant.EPOCH;
        skipCount = 0;
        realizedProfit = 0;
        currentSuggestion = null;
        nextSuggestion = null;
        lastHealthCheck = Instant.EPOCH;
        apiHealthy = false;
        apiHealthText = "Unknown";
        lastSuggestionDebug = "No fetch yet";
        nextBuyAttemptAt = Instant.EPOCH;

        overlayManager.add(overlay);
        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/graph.png");
        navButton = NavigationButton.builder().tooltip("Flipper0").icon(icon).panel(panel).build();
        clientToolbar.addNavigation(navButton);
    }

    @Override
    protected void shutDown()
    {
        log.info("Flipper0 shutting down");
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

        refreshApiHealthIfNeeded();
        refreshSuggestionsIfNeeded();
        cancelStaleOffers();
        collectOffers();

        FilterStats stats = new FilterStats();
        List<Suggestion> filtered = filteredSuggestions(stats);
        currentSuggestion = pickCurrent(filtered);
        nextSuggestion = filtered.size() > 1 ? filtered.get(Math.min(skipCount + 1, filtered.size() - 1)) : null;

        if (currentSuggestion == null)
        {
            statusText = suggestions.isEmpty() ? "No API suggestions" : "No eligible suggestions";
            log.info("No active recommendation. status='{}' parsed={} accepted={} invalidPrice={} lowVolume={} stale={} membersBlocked={} untradeable={} overBudget={} blacklisted={}",
                    statusText,
                    suggestions.size(),
                    stats.accepted,
                    stats.invalidPrice,
                    stats.lowVolume,
                    stats.stale,
                    stats.membersBlocked,
                    stats.untradeable,
                    stats.overBudget,
                    stats.blacklisted);
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
            if (Instant.now().isBefore(nextBuyAttemptAt))
            {
                long waitSec = Math.max(1, Duration.between(Instant.now(), nextBuyAttemptAt).getSeconds());
                statusText = "Waiting before next buy attempt (" + waitSec + "s)";
            }
            else
            {
                bought = tryBuySuggestion(currentSuggestion);
            }
        }
        else if (!sold)
        {
            statusText = "No free GE slots";
        }

        if (!sold && !bought && statusText.startsWith("Loaded"))
        {
            statusText = "Monitoring offers / awaiting fills";
        }

        log.debug("Loop status='{}' current={} next={} freeSlots={} coins={} api={}",
                statusText,
                currentSuggestion != null ? currentSuggestion.name : "none",
                nextSuggestion != null ? nextSuggestion.name : "none",
                getFreeEligibleSlots(),
                coinsText,
                apiHealthText);

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
            log.info("Loaded {} suggestions from API endpoints", fresh.size());
        }
        else if (suggestions.isEmpty())
        {
            statusText = "Suggestion API returned no items";
            log.warn("Suggestion API returned no items. {}", lastSuggestionDebug);
        }
        else
        {
            statusText = "Using cached suggestions (api empty)";
            log.warn("API returned no new items; using cached suggestions ({} cached). {}", suggestions.size(), lastSuggestionDebug);
        }

        lastFetch = Instant.now();
    }


    private void refreshApiHealthIfNeeded()
    {
        if (Instant.now().isBefore(lastHealthCheck.plusSeconds(60)))
        {
            return;
        }

        apiHealthy = isHealthEndpointUp();
        apiHealthText = apiHealthy ? "Up" : "Down";
        log.debug("Health check {} -> {}", HEALTH_ENDPOINT, apiHealthText);
        lastHealthCheck = Instant.now();
    }

    private boolean isHealthEndpointUp()
    {
        try
        {
            URL url = new URL(HEALTH_ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private List<Suggestion> filteredSuggestions(FilterStats stats)
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
                stats.invalidPrice++;
                continue;
            }
            if (runtimeBlacklist.contains(s.itemId))
            {
                stats.blacklisted++;
                continue;
            }
            if (s.minVolume > 0 && s.minVolume < minVolume)
            {
                stats.lowVolume++;
                continue;
            }
            if (s.ts > 0 && Instant.now().getEpochSecond() - s.ts > config.maxDataAgeSeconds())
            {
                stats.stale++;
                continue;
            }
            if (!memberWorld && s.members)
            {
                stats.membersBlocked++;
                continue;
            }
            if (!isTradeableForAccount(s.itemId, memberWorld))
            {
                stats.untradeable++;
                continue;
            }

            int effectiveBudget = Math.min(spendable, maxTradeBudget);
            if (effectiveBudget < s.buyPrice)
            {
                stats.overBudget++;
                continue;
            }

            stats.accepted++;
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
            log.info("Placed sell offer item={} name={} qty={} price={}", suggestion.itemId, suggestion.name, qty, price);
            return true;
        }

        statusText = "Sell failed for " + suggestion.name;
        log.warn("Failed placing sell offer item={} name={} qty={} price={}", suggestion.itemId, suggestion.name, qty, price);
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
            nextBuyAttemptAt = Instant.now().plusSeconds(3);
            statusText = "Not enough coins for " + suggestion.name;
            return false;
        }

        clearNumericDialogue();
        boolean ok = GrandExchangeAPI.startBuyOffer(suggestion.itemId, qty, suggestion.buyPrice) != null;
        clearNumericDialogue();

        if (ok)
        {
            activeOfferSince.put(suggestion.itemId, Instant.now());
            nextBuyAttemptAt = Instant.EPOCH;
            statusText = "Placed buy for " + suggestion.name + " x" + qty;
            log.info("Placed buy offer item={} name={} qty={} price={}", suggestion.itemId, suggestion.name, qty, suggestion.buyPrice);
            return true;
        }

        nextBuyAttemptAt = Instant.now().plusSeconds(5);
        statusText = "Buy failed for " + suggestion.name + " (cooldown 5s)";
        log.warn("Failed placing buy offer item={} name={} qty={} price={}; backing off until {}", suggestion.itemId, suggestion.name, qty, suggestion.buyPrice, nextBuyAttemptAt);
        return false;
    }

    private boolean isTradeableForAccount(int itemId, boolean memberWorld)
    {
        try
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
        catch (IllegalStateException ex)
        {
            log.debug("Skipping item-definition tradeability check off client thread for itemId={}", itemId);
            return true;
        }
        catch (Exception ex)
        {
            log.warn("Tradeability lookup failed for itemId={}: {}", itemId, ex.getMessage());
            return true;
        }
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
                log.info("Cancelled stale offer item={} state={} ageSec={}", offer.getItemId(), offer.getState(), Duration.between(started, now).getSeconds());
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
            log.debug("Collecting GE offers pass={}", i + 1);
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
        List<String> endpointDebug = new ArrayList<>();

        for (String endpoint : SUGGESTION_ENDPOINTS)
        {
            List<Suggestion> parsed = fetchSuggestionsFromEndpoint(endpoint, endpointDebug, 0);
            if (!parsed.isEmpty())
            {
                out.addAll(parsed);
                lastSuggestionDebug = String.join(" | ", endpointDebug);
                break;
            }
        }

        if (out.isEmpty())
        {
            lastSuggestionDebug = endpointDebug.isEmpty() ? "No endpoints attempted" : String.join(" | ", endpointDebug);
        }

        dedupeByItemIdKeepBest(out);
        return out;
    }

    private List<Suggestion> fetchSuggestionsFromEndpoint(String endpoint, List<String> endpointDebug, int redirectDepth)
    {
        List<Suggestion> out = new ArrayList<>();
        try
        {
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(6000);

            int code = conn.getResponseCode();
            if (code >= 300 && code < 400)
            {
                String location = conn.getHeaderField("Location");
                if (location == null || location.trim().isEmpty())
                {
                    endpointDebug.add(endpoint + " -> HTTP " + code + " (redirect with no location)");
                    return out;
                }

                if (redirectDepth >= MAX_REDIRECTS)
                {
                    endpointDebug.add(endpoint + " -> HTTP " + code + " (redirect limit reached)");
                    return out;
                }

                String resolved = new URL(url, location).toExternalForm();
                endpointDebug.add(endpoint + " -> HTTP " + code + " redirect to " + resolved);
                return fetchSuggestionsFromEndpoint(resolved, endpointDebug, redirectDepth + 1);
            }

            if (code < 200 || code >= 300)
            {
                endpointDebug.add(endpoint + " -> HTTP " + code);
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
            log.debug("Fetched endpoint={} bytes={}", endpoint, sb.length());
            List<JsonObject> objects = extractSuggestionObjects(root);
            if (objects.isEmpty())
            {
                endpointDebug.add(endpoint + " -> parsed 0 items (no suggestion array/object)");
                return out;
            }

            for (JsonObject object : objects)
            {
                Suggestion suggestion = parseSuggestionElement(object);
                if (suggestion != null)
                {
                    out.add(suggestion);
                }
            }

            endpointDebug.add(endpoint + " -> parsed " + out.size() + " valid suggestions from " + objects.size() + " records");
            if (out.isEmpty())
            {
                log.warn("Endpoint {} returned {} records but none parsed into valid suggestions", endpoint, objects.size());
            }

            return out;
        }
        catch (Exception ex)
        {
            endpointDebug.add(endpoint + " -> " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.warn("Suggestion fetch failed at endpoint {}: {}", endpoint, ex.toString());
            return out;
        }
    }

    private Suggestion parseSuggestionElement(JsonObject obj)
    {
        if (obj == null)
        {
            return null;
        }
        JsonObject itemObj = obj.has("item") && obj.get("item").isJsonObject() ? obj.getAsJsonObject("item") : obj;
        JsonObject pricesObj = obj.has("prices") && obj.get("prices").isJsonObject() ? obj.getAsJsonObject("prices") : obj;
        JsonObject metricsObj = obj.has("metrics") && obj.get("metrics").isJsonObject() ? obj.getAsJsonObject("metrics") : obj;

        Suggestion s = new Suggestion();
        s.itemId = pickPositive(
                getInt(itemObj, "itemId", -1),
                getInt(itemObj, "id", -1),
                getInt(itemObj, "item_id", -1),
                getInt(itemObj, "geItemId", -1),
                getInt(itemObj, "ge_item_id", -1),
                getInt(itemObj, "osrsId", -1),
                getInt(itemObj, "osrs_id", -1),
                getInt(obj, "itemId", -1),
                getInt(obj, "id", -1),
                getInt(obj, "item_id", -1),
                getInt(obj, "geItemId", -1),
                getInt(obj, "ge_item_id", -1),
                getInt(obj, "osrsId", -1),
                getInt(obj, "osrs_id", -1)
        );
        if (s.itemId <= 0)
        {
            return null;
        }

        s.name = getString(itemObj, "name",
                getString(itemObj, "itemName",
                    getString(itemObj, "item_name",
                        getString(obj, "name",
                            getString(obj, "itemName", "Item " + s.itemId)))));

        int high = pickPositive(
                getInt(obj, "high", -1),
                getInt(obj, "highPrice", -1),
                getInt(obj, "high_price", -1),
                getInt(obj, "sellPrice", -1),
                getInt(obj, "sell_price", -1),
                getInt(obj, "sell", -1),
                getInt(obj, "targetSell", -1),
                getInt(obj, "target_sell", -1),
                getInt(pricesObj, "high", -1),
                getInt(pricesObj, "sell", -1),
                getInt(pricesObj, "sellPrice", -1),
                getInt(pricesObj, "sell_price", -1),
                getInt(itemObj, "high", -1)
        );

        int low = pickPositive(
                getInt(obj, "low", -1),
                getInt(obj, "lowPrice", -1),
                getInt(obj, "low_price", -1),
                getInt(obj, "buyPrice", -1),
                getInt(obj, "buy_price", -1),
                getInt(obj, "buy", -1),
                getInt(obj, "targetBuy", -1),
                getInt(obj, "target_buy", -1),
                getInt(pricesObj, "low", -1),
                getInt(pricesObj, "buy", -1),
                getInt(pricesObj, "buyPrice", -1),
                getInt(pricesObj, "buy_price", -1),
                getInt(itemObj, "low", -1)
        );

        int buyCandidate = pickPositive(
                getInt(obj, "buyPrice", -1),
                getInt(obj, "buy_price", -1),
                getInt(pricesObj, "buyPrice", -1),
                getInt(pricesObj, "buy_price", -1),
                getInt(pricesObj, "buy", -1),
                low
        );

        int sellCandidate = pickPositive(
                getInt(obj, "sellPrice", -1),
                getInt(obj, "sell_price", -1),
                getInt(pricesObj, "sellPrice", -1),
                getInt(pricesObj, "sell_price", -1),
                getInt(pricesObj, "sell", -1),
                high
        );

        s.buyPrice = buyCandidate;
        s.sellPrice = sellCandidate;

        if (s.buyPrice > 0 && s.sellPrice > 0 && s.sellPrice <= s.buyPrice && s.sellPrice != s.buyPrice)
        {
            int minPrice = Math.min(s.buyPrice, s.sellPrice);
            int maxPrice = Math.max(s.buyPrice, s.sellPrice);
            log.debug("Swapping inverted prices for itemId={} buy={} sell={}", s.itemId, s.buyPrice, s.sellPrice);
            s.buyPrice = minPrice;
            s.sellPrice = maxPrice;
        }

        int margin = pickPositive(
                getInt(obj, "margin", -1),
                getInt(obj, "marginGp", -1),
                getInt(obj, "margin_gp", -1),
                getInt(metricsObj, "margin", -1),
                getInt(metricsObj, "marginGp", -1)
        );
        if (s.buyPrice > 0 && s.sellPrice > 0 && s.sellPrice <= s.buyPrice && margin > 0)
        {
            s.sellPrice = s.buyPrice + margin;
        }

        s.minVolume = pickPositive(
                getInt(obj, "minVolume", -1),
                getInt(obj, "volume", -1),
                getInt(obj, "volume_5m", -1),
                getInt(obj, "volume5m", -1),
                getInt(obj, "fiveMinuteVolume", -1),
                getInt(obj, "min_volume", -1),
                getInt(obj, "dailyVolume", -1),
                getInt(obj, "volume1h", -1),
                getInt(obj, "hourlyVolume", -1),
                getInt(obj, "daily_volume", -1),
                getInt(metricsObj, "minVolume", -1),
                getInt(metricsObj, "volume", -1),
                getInt(metricsObj, "volume_5m", -1),
                getInt(metricsObj, "volume5m", -1),
                getInt(metricsObj, "dailyVolume", -1),
                getInt(metricsObj, "volume1h", -1),
                getInt(metricsObj, "daily_volume", -1),
                getInt(itemObj, "volume", -1),
                0
        );
        s.geLimit = Math.max(1, pickPositive(
                getInt(itemObj, "buyLimit", -1),
                getInt(itemObj, "buy_limit", -1),
                getInt(itemObj, "limit", -1),
                getInt(obj, "buyLimit", -1),
                getInt(obj, "buy_limit", -1),
                getInt(obj, "geLimit", -1),
                getInt(obj, "limit", -1),
                70
        ));

        boolean membersFromItem = getBoolean(itemObj, "members", false);
        s.members = getBoolean(obj, "members", membersFromItem);

        s.score = pickPositiveDouble(
                getDouble(obj, "score", -1),
                getDouble(obj, "rankScore", -1),
                getDouble(obj, "opportunityScore", -1),
                getDouble(metricsObj, "score", -1),
                0.0
        );

        double roiRaw = pickPositiveDouble(
                getDouble(obj, "roi", -1),
                getDouble(obj, "roiPct", -1),
                getDouble(obj, "roi_pct", -1),
                getDouble(metricsObj, "roi", -1),
                -1
        );

        s.ts = normalizeTimestamp(pickPositiveLong(
                getLong(obj, "timestamp", -1),
                getLong(obj, "updatedAt", -1),
                getLong(obj, "updated_at", -1),
                getLong(metricsObj, "updatedAt", -1),
                getLong(metricsObj, "updated_at", -1),
                getLong(itemObj, "updatedAt", -1),
                Instant.now().getEpochSecond()
        ));

        if (s.buyPrice <= 0 || s.sellPrice <= s.buyPrice)
        {
            return null;
        }

        s.roiPct = ((s.sellPrice - s.buyPrice) * 100.0) / Math.max(1, s.buyPrice);
        if (roiRaw > 0)
        {
            s.roiPct = roiRaw <= 1.0 ? (roiRaw * 100.0) : roiRaw;
        }

        if (s.score <= 0.0)
        {
            s.score = s.roiPct + (Math.log10(Math.max(1, s.minVolume)) * 1.8);
        }
        return s;
    }

    private List<JsonObject> extractSuggestionObjects(JsonElement root)
    {
        List<JsonObject> out = new ArrayList<>();
        if (root == null || root.isJsonNull())
        {
            return out;
        }

        if (root.isJsonArray())
        {
            addObjectsFromArray(root.getAsJsonArray(), out);
            return out;
        }
        if (!root.isJsonObject())
        {
            return out;
        }

        JsonObject obj = root.getAsJsonObject();
        String[] keys = {"suggestions", "data", "items", "results", "opportunities", "recommendations", "rows"};
        for (String key : keys)
        {
            if (!obj.has(key) || obj.get(key).isJsonNull())
            {
                continue;
            }

            JsonElement child = obj.get(key);
            if (child.isJsonArray())
            {
                addObjectsFromArray(child.getAsJsonArray(), out);
                if (!out.isEmpty())
                {
                    return out;
                }
            }
            else if (child.isJsonObject())
            {
                List<JsonObject> nested = extractSuggestionObjects(child);
                if (!nested.isEmpty())
                {
                    return nested;
                }
            }
        }

        if (looksLikeSuggestionObject(obj))
        {
            out.add(obj);
        }

        return out;
    }

    private void addObjectsFromArray(JsonArray arr, List<JsonObject> out)
    {
        for (JsonElement e : arr)
        {
            if (e != null && e.isJsonObject())
            {
                out.add(e.getAsJsonObject());
            }
        }
    }

    private boolean looksLikeSuggestionObject(JsonObject obj)
    {
        return obj.has("item") || obj.has("itemId") || obj.has("id") || obj.has("buyPrice") || obj.has("sellPrice")
                || obj.has("buy_price") || obj.has("sell_price") || obj.has("prices")
                || obj.has("item_id") || obj.has("ge_item_id") || obj.has("geItemId") || obj.has("osrs_id");
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
                apiHealthText,
                lastSuggestionDebug,
                currentSuggestion,
                new ArrayList<>(filteredSuggestions(new FilterStats())),
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
        return config.enabled() && config.showOverlay() && client.getGameState() == GameState.LOGGED_IN;
    }

    public String getStatusText() { return statusText; }
    public String getSlotsText() { return slotsText; }
    public String getApiHealthText() { return apiHealthText; }
    public Suggestion getCurrentSuggestion() { return currentSuggestion; }
    public Suggestion getNextSuggestion() { return nextSuggestion; }


    public int getOverlayOffsetY()
    {
        return config.overlayOffsetY();
    }

    public String getGpPerHourText()
    {
        long secs = Math.max(1, Duration.between(startedAt, Instant.now()).getSeconds());
        long gphr = (realizedProfit * 3600L) / secs;
        return String.format("%,d", gphr);
    }
}
