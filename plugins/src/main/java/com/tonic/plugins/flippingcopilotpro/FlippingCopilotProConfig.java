package com.tonic.plugins.flippingcopilotpro;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("flippingCopilotPro")
public interface FlippingCopilotProConfig extends Config
{
    enum ReofferInterval
    {
        FIVE_MINUTES,
        THIRTY_MINUTES,
        ONE_HOUR
    }

    @ConfigItem(keyName = "standaloneMode", name = "Mode", description = "Runs locally with VitaLite APIs only (no account or premium authentication)", position = -1)
    default String standaloneMode() { return "Standalone local automation"; }

    @ConfigItem(keyName = "enabled", name = "Enable automation", description = "Master toggle for automatic GE trading", position = 0)
    default boolean enabled() { return false; }

    @ConfigItem(keyName = "targetItemsPerCycle", name = "Diversify across top items", description = "Max number of items to actively trade each cycle", position = 1)
    default int targetItemsPerCycle() { return 4; }

    @ConfigItem(keyName = "maxGpPerTrade", name = "Max GP per trade", description = "Per-item buy budget cap", position = 2)
    default int maxGpPerTrade() { return 1_500_000; }

    @ConfigItem(keyName = "minRoiPercent", name = "Min ROI %", description = "Minimum spread ROI required", position = 3)
    default double minRoiPercent() { return 1.0; }

    @ConfigItem(keyName = "minFiveMinVolume", name = "Min 5m volume", description = "Minimum 5-minute volume threshold", position = 4)
    default int minFiveMinVolume() { return 20; }

    @ConfigItem(keyName = "buyPriceBumpPercent", name = "Buy bump %", description = "Percent above low for faster buy fills", position = 5)
    default double buyPriceBumpPercent() { return 0.6; }

    @ConfigItem(keyName = "sellPriceUndercutPercent", name = "Sell undercut %", description = "Percent below high for normal sells", position = 6)
    default double sellPriceUndercutPercent() { return 0.6; }

    @ConfigItem(keyName = "sellTimeoutSeconds", name = "Sell timeout (seconds)", description = "Force panic sell after this hold duration", position = 7)
    default int sellTimeoutSeconds() { return 150; }

    @ConfigItem(keyName = "panicSellDiscountPercent", name = "Panic sell discount %", description = "Percent below low used for timed-out sells", position = 8)
    default double panicSellDiscountPercent() { return 0.8; }

    @ConfigItem(keyName = "reofferInterval", name = "Cancel + re-offer interval", description = "How long to wait before canceling stuck offers and re-entering", position = 9)
    default ReofferInterval reofferInterval() { return ReofferInterval.FIVE_MINUTES; }

    @ConfigItem(keyName = "humanDelayMinMs", name = "Human delay min (ms)", description = "Random anti-ban delay lower bound for each GE action", position = 10)
    default int humanDelayMinMs() { return 120; }

    @ConfigItem(keyName = "humanDelayMaxMs", name = "Human delay max (ms)", description = "Random anti-ban delay upper bound for each GE action", position = 11)
    default int humanDelayMaxMs() { return 420; }

    @ConfigItem(keyName = "marketSourceLabel", name = "Market source label", description = "Shown in overlay; using prices.runescape.wiki/osrs", position = 12)
    default String marketSourceLabel() { return "prices.runescape.wiki/osrs"; }

    @ConfigItem(keyName = "loopDelayTicks", name = "Loop delay (ticks)", description = "Ticks between automation loops", position = 13)
    default int loopDelayTicks() { return 2; }

    @ConfigItem(keyName = "autoOpenGe", name = "Auto-open GE", description = "Open GE via nearby clerk when needed", position = 14)
    default boolean autoOpenGe() { return true; }
}
