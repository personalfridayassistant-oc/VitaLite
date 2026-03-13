package com.tonic.plugins.marketmentor;

import java.time.Duration;

public enum WebhookInterval
{
    OFF(Duration.ZERO),
    FIFTEEN_MIN(Duration.ofMinutes(15)),
    THIRTY_MIN(Duration.ofMinutes(30)),
    ONE_HOUR(Duration.ofHours(1)),
    THREE_HOUR(Duration.ofHours(3)),
    SIX_HOUR(Duration.ofHours(6));

    private final Duration duration;

    WebhookInterval(Duration duration)
    {
        this.duration = duration;
    }

    public Duration getDuration()
    {
        return duration;
    }
}
