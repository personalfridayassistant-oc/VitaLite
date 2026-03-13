package com.tonic.plugins.zombiepirates;

import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

import javax.inject.Inject;
import java.awt.*;

public class ZombiePiratesOverlay extends OverlayPanel
{
    private final ZombiePiratesPlugin plugin;
    private final ZombiePiratesConfig config;

    @Inject
    public ZombiePiratesOverlay(ZombiePiratesPlugin plugin, ZombiePiratesConfig config)
    {
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.BOTTOM_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        panelComponent.getChildren().clear();

        if (config.minimizedOverlay())
        {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("ZombiePirates")
                    .right(plugin.getRuntimeText())
                    .build());
            return super.render(graphics);
        }

        panelComponent.getChildren().add(LineComponent.builder().left("Zombie Pirates").right(plugin.getStateText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Kills").right(String.valueOf(plugin.getKills())).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Loot GP").right(String.format("%,d", plugin.getLootValue())).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Runtime").right(plugin.getRuntimeText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Trip Status").right(plugin.getTripText()).build());

        return super.render(graphics);
    }
}
