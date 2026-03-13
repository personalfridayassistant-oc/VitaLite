package com.tonic.plugins.flippingcopilotpro;

import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class FlippingCopilotProOverlay extends OverlayPanel
{
    private final FlippingCopilotProPlugin plugin;

    @Inject
    public FlippingCopilotProOverlay(FlippingCopilotProPlugin plugin)
    {
        this.plugin = plugin;
        setPosition(OverlayPosition.BOTTOM_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        panelComponent.getChildren().clear();

        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Flipping Copilot Pro")
                .color(new Color(88, 166, 255))
                .build());

        panelComponent.getChildren().add(LineComponent.builder().left("Runtime").right(plugin.getRuntimeText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("GP Made").right(String.format("%,d", plugin.getGpMade())).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Slots").right(plugin.getSlotText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Source").right(plugin.getMarketSourceText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Current Buy").right(plugin.getProposedItemText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Best ROI").right(plugin.getBestReturnItemText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Status").right(plugin.getStatusText()).build());

        return super.render(graphics);
    }
}
