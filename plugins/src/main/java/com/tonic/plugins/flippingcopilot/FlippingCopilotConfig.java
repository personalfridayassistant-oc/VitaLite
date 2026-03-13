package com.tonic.plugins.flippingcopilot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("flippingCopilot")
public interface FlippingCopilotConfig extends Config
{
    @ConfigItem(keyName = "enabled", name = "Enable automation", description = "Master toggle for automatic GE trading", position = 0)
    default boolean enabled()
    {
        return false;
    }

    @ConfigItem(
            keyName = "candidateItemIds",
            name = "Candidate item IDs",
            description = "CSV list of item ids the smart picker can evaluate (e.g. 11212, 2364, 1516)",
            position = 1
    )
    default String candidateItemIds()
    {
        return "11212, 2364, 1516, 314, 453";
    }

    @ConfigItem(
            keyName = "maxGpPerTrade",
            name = "Max GP per trade",
            description = "Budget cap per item when opening buy offers",
            position = 2
    )
    default int maxGpPerTrade()
    {
        return 1_500_000;
    }

    @ConfigItem(
            keyName = "targetItemsPerCycle",
            name = "Items to trade per cycle",
            description = "How many top opportunities to trade from the candidate set",
            position = 3
    )
    default int targetItemsPerCycle()
    {
        return 3;
    }

    @ConfigItem(
            keyName = "minRoiPercent",
            name = "Min ROI %",
            description = "Minimum spread ROI required for a candidate",
            position = 4
    )
    default double minRoiPercent()
    {
        return 1.0;
    }

    @ConfigItem(
            keyName = "minFiveMinVolume",
            name = "Min 5m volume",
            description = "Minimum 5-minute traded volume to consider an item",
            position = 5
    )
    default int minFiveMinVolume()
    {
        return 20;
    }

    @ConfigItem(
            keyName = "buyPriceBumpPercent",
            name = "Buy bump %",
            description = "Percent added to low price for faster fills",
            position = 6
    )
    default double buyPriceBumpPercent()
    {
        return 0.6;
    }

    @ConfigItem(
            keyName = "sellPriceUndercutPercent",
            name = "Sell undercut %",
            description = "Percent below high price when listing normal sells",
            position = 7
    )
    default double sellPriceUndercutPercent()
    {
        return 0.6;
    }

    @ConfigItem(
            keyName = "sellTimeoutSeconds",
            name = "Sell timeout (seconds)",
            description = "After this many seconds holding inventory, force a faster liquidation sell",
            position = 8
    )
    default int sellTimeoutSeconds()
    {
        return 150;
    }

    @ConfigItem(
            keyName = "panicSellDiscountPercent",
            name = "Panic sell discount %",
            description = "Discount from low price used after sell timeout",
            position = 9
    )
    default double panicSellDiscountPercent()
    {
        return 0.8;
    }

    @ConfigItem(
            keyName = "staleOfferSeconds",
            name = "Cancel stale offer (seconds)",
            description = "Cancel an active offer if unchanged for this many seconds",
            position = 10
    )
    default int staleOfferSeconds()
    {
        return 70;
    }

    @ConfigItem(keyName = "loopDelayTicks", name = "Loop delay (ticks)", description = "Ticks between checks", position = 11)
    default int loopDelayTicks()
    {
        return 2;
    }

    @ConfigItem(keyName = "autoOpenGe", name = "Auto-open GE", description = "Open GE via nearby clerk when needed", position = 12)
    default boolean autoOpenGe()
    {
        return true;
    }
}
