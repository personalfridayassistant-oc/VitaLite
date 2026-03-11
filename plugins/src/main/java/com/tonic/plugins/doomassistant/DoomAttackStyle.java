package com.tonic.plugins.doomassistant;

import com.tonic.api.widgets.PrayerAPI;

enum DoomAttackStyle
{
    MAGIC("Magic", PrayerAPI.PROTECT_FROM_MAGIC),
    RANGED("Ranged", PrayerAPI.PROTECT_FROM_MISSILES),
    MELEE("Melee", PrayerAPI.PROTECT_FROM_MELEE),
    UNKNOWN("Unknown", null);

    private final String display;
    private final PrayerAPI overhead;

    DoomAttackStyle(String display, PrayerAPI overhead)
    {
        this.display = display;
        this.overhead = overhead;
    }

    String getDisplay()
    {
        return display;
    }

    PrayerAPI getOverhead()
    {
        return overhead;
    }
}
