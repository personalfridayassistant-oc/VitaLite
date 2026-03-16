package com.tonic.plugins.flipper0;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("flipper0")
public interface Flipper0Config extends Config
{
    @ConfigItem(keyName = "enabled", name = "Enable automation", description = "Enable Flipper0 automation loop", position = 0)
    default boolean enabled() { return false; }

    @ConfigItem(keyName = "coinReserve", name = "Coin reserve", description = "Keep this many coins unspent", position = 1)
    default int coinReserve() { return 250_000; }

    @ConfigItem(keyName = "maxGpPerTrade", name = "Max GP per trade", description = "Maximum spend for a single item", position = 2)
    default int maxGpPerTrade() { return 5_000_000; }

    @ConfigItem(keyName = "minVolume", name = "Min volume", description = "Minimum volume required in API suggestion", position = 3)
    default int minVolume() { return 100; }

    @ConfigItem(keyName = "maxDataAgeSeconds", name = "Max suggestion age (seconds)", description = "Ignore stale suggestions", position = 4)
    default int maxDataAgeSeconds() { return 360; }

    @ConfigItem(keyName = "staleOfferSeconds", name = "Cancel stale offers (seconds)", description = "Cancel offers older than this", position = 5)
    default int staleOfferSeconds() { return 90; }

    @ConfigItem(keyName = "blacklistItemIds", name = "Blacklist item IDs", description = "CSV item IDs to always skip", position = 6)
    default String blacklistItemIds() { return ""; }
}
