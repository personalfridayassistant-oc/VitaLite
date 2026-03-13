package com.tonic.plugins.flippingcopilot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("flippingCopilot")
public interface FlippingCopilotConfig extends Config
{
    @ConfigItem(keyName = "enabled", name = "Enable automation", description = "Master toggle for automatic GE trading", position = 0)
    default boolean enabled() { return false; }

    @ConfigItem(keyName = "candidateItemIds", name = "Candidate item IDs", description = "CSV item ids to evaluate (e.g. 11212,2364,1516)", position = 1)
    default String candidateItemIds() { return "11212,2364,1516,314,453"; }

    @ConfigItem(keyName = "targetItemsPerCycle", name = "Diversify across top items", description = "Max number of items to actively trade each cycle", position = 2)
    default int targetItemsPerCycle() { return 4; }

    @ConfigItem(keyName = "maxGpPerTrade", name = "Max GP per trade", description = "Per-item buy budget cap", position = 3)
    default int maxGpPerTrade() { return 1_500_000; }

    @ConfigItem(keyName = "minRoiPercent", name = "Min ROI %", description = "Minimum spread ROI required", position = 4)
    default double minRoiPercent() { return 1.0; }

    @ConfigItem(keyName = "minFiveMinVolume", name = "Min 5m volume", description = "Minimum 5-minute volume threshold", position = 5)
    default int minFiveMinVolume() { return 20; }

    @ConfigItem(keyName = "buyPriceBumpPercent", name = "Buy bump %", description = "Percent above low for faster buy fills", position = 6)
    default double buyPriceBumpPercent() { return 0.6; }

    @ConfigItem(keyName = "sellPriceUndercutPercent", name = "Sell undercut %", description = "Percent below high for normal sells", position = 7)
    default double sellPriceUndercutPercent() { return 0.6; }

    @ConfigItem(keyName = "sellTimeoutSeconds", name = "Sell timeout (seconds)", description = "Force panic sell after this hold duration", position = 8)
    default int sellTimeoutSeconds() { return 150; }

    @ConfigItem(keyName = "panicSellDiscountPercent", name = "Panic sell discount %", description = "Percent below low used for timed-out sells", position = 9)
    default double panicSellDiscountPercent() { return 0.8; }

    @ConfigItem(keyName = "staleOfferSeconds", name = "Cancel stale offer (seconds)", description = "Cancel stuck active offers after this duration", position = 10)
    default int staleOfferSeconds() { return 70; }

    @ConfigItem(keyName = "humanDelayMinMs", name = "Human delay min (ms)", description = "Random anti-ban delay lower bound for each GE action", position = 11)
    default int humanDelayMinMs() { return 120; }

    @ConfigItem(keyName = "humanDelayMaxMs", name = "Human delay max (ms)", description = "Random anti-ban delay upper bound for each GE action", position = 12)
    default int humanDelayMaxMs() { return 420; }

    @ConfigItem(keyName = "marketSourceLabel", name = "Market source label", description = "Shown in overlay; priority should remain prices.runescape.wiki/osrs", position = 13)
    default String marketSourceLabel() { return "prices.runescape.wiki/osrs (primary)"; }

    @ConfigItem(keyName = "loopDelayTicks", name = "Loop delay (ticks)", description = "Ticks between automation loops", position = 14)
    default int loopDelayTicks() { return 2; }

    @ConfigItem(keyName = "autoOpenGe", name = "Auto-open GE", description = "Open GE via nearby clerk when needed", position = 15)
    default boolean autoOpenGe() { return true; }
}
