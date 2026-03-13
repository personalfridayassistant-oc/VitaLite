package com.tonic.plugins.flippingcopilot;

import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

import javax.inject.Inject;
import java.awt.*;

public class FlippingCopilotOverlay extends OverlayPanel
{
    private final FlippingCopilotPlugin plugin;

    @Inject
    public FlippingCopilotOverlay(FlippingCopilotPlugin plugin)
    {
        this.plugin = plugin;
        setPosition(OverlayPosition.BOTTOM_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        panelComponent.getChildren().clear();

        panelComponent.getChildren().add(LineComponent.builder().left("Flipping Copilot").right(plugin.getRuntimeText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("GP Made").right(String.format("%,d", plugin.getGpMade())).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Proposed Buy").right(plugin.getProposedItemText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Best ROI").right(plugin.getBestReturnItemText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Status").right(plugin.getStatusText()).build());

        return super.render(graphics);
    }
}
