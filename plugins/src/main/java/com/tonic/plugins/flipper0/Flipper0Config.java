package com.tonic.plugins.flipper0;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("flipper0")
public interface Flipper0Config extends Config
{
    @ConfigItem(keyName = "enabled", name = "Enable automation", description = "Enable Flipper0 automation loop", position = 0)
    default boolean enabled() { return false; }

    @ConfigItem(keyName = "autoOpenGe", name = "Auto-open GE", description = "Try to open GE via nearby clerk if closed", position = 1)
    default boolean autoOpenGe() { return true; }

    @ConfigItem(keyName = "refreshSeconds", name = "Suggestion refresh (sec)", description = "How often to fetch new suggestions", position = 2)
    default int refreshSeconds() { return 5; }

    @ConfigItem(keyName = "coinReserve", name = "Coin reserve", description = "Keep this many coins unspent", position = 3)
    default int coinReserve() { return 250_000; }

    @ConfigItem(keyName = "maxGpPerTrade", name = "Max GP per trade", description = "Maximum spend for a single item", position = 4)
    default int maxGpPerTrade() { return 100_000_000; }

    @ConfigItem(keyName = "minVolume", name = "Min volume", description = "Minimum volume required in API suggestion", position = 5)
    default int minVolume() { return 10; }

    @ConfigItem(keyName = "maxDataAgeSeconds", name = "Max suggestion age (seconds)", description = "Ignore stale suggestions", position = 6)
    default int maxDataAgeSeconds() { return 240; }

    @ConfigItem(keyName = "staleOfferSeconds", name = "Cancel stale offers (seconds)", description = "Cancel offers older than this", position = 7)
    default int staleOfferSeconds() { return 90; }

    @ConfigItem(keyName = "loopDelayTicks", name = "Loop delay (ticks)", description = "Delay between trading cycles", position = 8)
    default int loopDelayTicks() { return 1; }


    @ConfigItem(keyName = "showOverlay", name = "Show overlay", description = "Show Flipper0 in-game overlay", position = 9)
    default boolean showOverlay() { return true; }

    @ConfigItem(keyName = "overlayOffsetY", name = "Overlay offset Y", description = "Vertical offset for Flipper0 overlay", position = 10)
    default int overlayOffsetY() { return 40; }

    @ConfigItem(keyName = "blacklistItemIds", name = "Blacklist item IDs", description = "CSV item IDs to always skip", position = 11)
    default String blacklistItemIds() { return ""; }
}
