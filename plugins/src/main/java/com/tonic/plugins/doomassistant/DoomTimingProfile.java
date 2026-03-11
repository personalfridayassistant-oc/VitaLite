package com.tonic.plugins.doomassistant;

/**
 * Heuristic timing buckets by delve band.
 */
final class DoomTimingProfile
{
    private final int shockwaveCadenceTicks;
    private final int rockThrowCadenceTicks;
    private final int hazardAlertLeadTicks;

    private DoomTimingProfile(int shockwaveCadenceTicks, int rockThrowCadenceTicks, int hazardAlertLeadTicks)
    {
        this.shockwaveCadenceTicks = shockwaveCadenceTicks;
        this.rockThrowCadenceTicks = rockThrowCadenceTicks;
        this.hazardAlertLeadTicks = hazardAlertLeadTicks;
    }

    static DoomTimingProfile forDelve(int delve)
    {
        if (delve <= 4)
        {
            return new DoomTimingProfile(22, 18, 3);
        }

        if (delve <= 6)
        {
            return new DoomTimingProfile(17, 14, 3);
        }

        return new DoomTimingProfile(14, 11, 2);
    }

    int getShockwaveCadenceTicks()
    {
        return shockwaveCadenceTicks;
    }

    int getRockThrowCadenceTicks()
    {
        return rockThrowCadenceTicks;
    }

    int getHazardAlertLeadTicks()
    {
        return hazardAlertLeadTicks;
    }
}
