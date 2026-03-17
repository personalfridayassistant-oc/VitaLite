package com.tonic.plugins.flipperone;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("flipperone")
public interface FlipperOneConfig extends Config
{
    @ConfigItem(keyName = "enabled", name = "Enable automation", description = "Enable FlipperOne automation loop", position = 0)
    default boolean enabled() { return false; }

    @ConfigItem(keyName = "autoOpenGe", name = "Auto-open GE", description = "Try to open GE via nearby clerk if closed", position = 1)
    default boolean autoOpenGe() { return true; }

    @ConfigItem(keyName = "refreshSeconds", name = "Suggestion refresh (sec)", description = "How often to fetch new suggestions", position = 2)
    default int refreshSeconds() { return 5; }

    @ConfigItem(keyName = "coinReserve", name = "Coin reserve", description = "Keep this many coins unspent", position = 3)
    default int coinReserve() { return 250_000; }

    @ConfigItem(keyName = "maxGpPerTrade", name = "Max GP per trade", description = "Maximum spend for a single item", position = 4)
    default int maxGpPerTrade() { return 100_000_000; }

    @ConfigItem(keyName = "maxBankPctPerTrade", name = "Max bankroll % per trade", description = "Max % of available coins to spend on a single suggestion", position = 5)
    default int maxBankPctPerTrade() { return 25; }

    @ConfigItem(keyName = "minVolume", name = "Min volume", description = "Minimum volume required in API suggestion", position = 6)
    default int minVolume() { return 10; }

    @ConfigItem(keyName = "minScore", name = "Min API score", description = "Minimum API score for suggestions/backtests", position = 7)
    default double minScore() { return 0.70; }

    @ConfigItem(keyName = "minRoiPct", name = "Min ROI %", description = "Skip items under this gross return percentage", position = 8)
    default double minRoiPct() { return 0.35; }

    @ConfigItem(keyName = "horizonMinutes", name = "Backtest horizon (minutes)", description = "Horizon used when querying backtests endpoint", position = 9)
    default int horizonMinutes() { return 30; }

    @ConfigItem(keyName = "lookbackHours", name = "Backtest lookback (hours)", description = "Lookback window used when querying backtests endpoint", position = 10)
    default int lookbackHours() { return 24; }

    @ConfigItem(keyName = "maxDataAgeSeconds", name = "Max suggestion age (seconds)", description = "Ignore stale suggestions", position = 11)
    default int maxDataAgeSeconds() { return 240; }

    @ConfigItem(keyName = "staleOfferSeconds", name = "Cancel stale offers (seconds)", description = "Cancel offers older than this", position = 12)
    default int staleOfferSeconds() { return 90; }

    @ConfigItem(keyName = "loopDelayTicks", name = "Loop delay (ticks)", description = "Delay between trading cycles", position = 13)
    default int loopDelayTicks() { return 1; }


    @ConfigItem(keyName = "cycleAfterAttempts", name = "Cycle after failed attempts", description = "Move to next item after this many buy failures/stale cancellations", position = 14)
    default int cycleAfterAttempts() { return 3; }

    @ConfigItem(keyName = "apiBaseUrl", name = "VitaLite API base URL", description = "Example: http://192.168.1.27:3015/api/v1", position = 15)
    default String apiBaseUrl() { return "http://192.168.1.27:3015/api/v1"; }

    @ConfigItem(keyName = "f2pOnly", name = "F2P-only suggestions", description = "Only fetch and trade free-to-play (non-members) items", position = 16)
    default boolean f2pOnly() { return false; }

    @ConfigItem(keyName = "showOverlay", name = "Show overlay", description = "Show FlipperOne in-game overlay", position = 17)
    default boolean showOverlay() { return true; }

    @ConfigItem(keyName = "overlayOffsetY", name = "Overlay offset Y", description = "Vertical offset for FlipperOne overlay", position = 18)
    default int overlayOffsetY() { return 40; }

    @ConfigItem(keyName = "blacklistItemIds", name = "Blacklist item IDs", description = "CSV item IDs to always skip", position = 19)
    default String blacklistItemIds() { return ""; }
}
