package com.tonic.plugins.marketmentor;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("marketMentor")
public interface MarketMentorConfig extends Config
{
    @ConfigItem(keyName = "enabled", name = "Enable automation", description = "Master toggle for fully automated GE trading", position = 0)
    default boolean enabled() { return false; }

    @ConfigItem(keyName = "maxUniverseItems", name = "Auto-scan item universe", description = "Maximum top-volume items auto-selected from Wiki 5m data", position = 1)
    default int maxUniverseItems() { return 1400; }

    @ConfigItem(keyName = "maxItemsPerCycle", name = "Max items per cycle", description = "Diversify by splitting buy attempts across top opportunities", position = 2)
    default int maxItemsPerCycle() { return 3; }

    @ConfigItem(keyName = "coinReserve", name = "Coin reserve", description = "Always keep at least this many coins unspent", position = 3)
    default int coinReserve() { return 250_000; }

    @ConfigItem(keyName = "maxGpPerTrade", name = "Max GP per trade", description = "Upper budget cap per item", position = 4)
    default int maxGpPerTrade() { return 3_000_000; }

    @ConfigItem(keyName = "maxBudgetPct", name = "Max budget % per item", description = "Do not spend more than this percent of available coins on one item", position = 5)
    default double maxBudgetPct() { return 35.0; }

    @ConfigItem(keyName = "reservedSlots", name = "Reserved GE slots", description = "Keep this many eligible GE slots empty", position = 6)
    default int reservedSlots() { return 0; }

    @ConfigItem(keyName = "minMarginPct", name = "Min margin %", description = "Minimum gross spread percent required before tax", position = 7)
    default double minMarginPct() { return 2.5; }

    @ConfigItem(keyName = "minNetRoiPct", name = "Min net ROI %", description = "Minimum expected ROI after estimated GE tax", position = 8)
    default double minNetRoiPct() { return 1.8; }

    @ConfigItem(keyName = "minProfitMarginGp", name = "Min profit margin (gp)", description = "Skip opportunities where buy->sell margin is below this GP", position = 9)
    default int minProfitMarginGp() { return 1000; }

    @ConfigItem(keyName = "minFiveMinuteVolume", name = "Min 5m volume", description = "Minimum 5-minute low/high volume for liquidity", position = 10)
    default int minFiveMinuteVolume() { return 60; }

    @ConfigItem(keyName = "maxDataAgeSeconds", name = "Max quote age (seconds)", description = "Skip items with stale latest quote timestamps", position = 11)
    default int maxDataAgeSeconds() { return 1800; }

    @ConfigItem(keyName = "buyBumpPct", name = "Buy bump %", description = "Buy above low price for faster fills", position = 12)
    default double buyBumpPct() { return 0.6; }

    @ConfigItem(keyName = "sellUndercutPct", name = "Sell undercut %", description = "Sell below high price for quicker exits", position = 13)
    default double sellUndercutPct() { return 0.7; }

    @ConfigItem(keyName = "panicDiscountPct", name = "Panic discount %", description = "Discount below low used when a holding times out", position = 14)
    default double panicDiscountPct() { return 1.0; }

    @ConfigItem(keyName = "staleOfferSeconds", name = "Stale offer cancel (seconds)", description = "Abort active offers older than this timeout", position = 15)
    default int staleOfferSeconds() { return 90; }

    @ConfigItem(keyName = "holdingTimeoutSeconds", name = "Holding timeout (seconds)", description = "Switch to panic sell after this duration", position = 16)
    default int holdingTimeoutSeconds() { return 210; }

    @ConfigItem(keyName = "loopDelayTicks", name = "Loop delay (ticks)", description = "Ticks between automation cycles", position = 17)
    default int loopDelayTicks() { return 2; }

    @ConfigItem(keyName = "autoOpenGe", name = "Auto-open GE", description = "Open GE by interacting nearby clerk when closed", position = 18)
    default boolean autoOpenGe() { return true; }

    @ConfigItem(keyName = "blacklistItemIds", name = "Blacklist item IDs", description = "CSV ids to skip entirely (e.g. 4151,11286)", position = 19)
    default String blacklistItemIds() { return ""; }

    @ConfigItem(keyName = "discordWebhookUrl", name = "Discord webhook URL", description = "Optional webhook URL for summary notifications", position = 20)
    default String discordWebhookUrl() { return ""; }

    @ConfigItem(keyName = "discordWebhookInterval", name = "Discord notify interval", description = "How often to send webhook summary updates", position = 21)
    default WebhookInterval discordWebhookInterval() { return WebhookInterval.ONE_HOUR; }
}
