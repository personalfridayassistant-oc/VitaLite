package com.tonic.plugins.marketmentor;

import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

public class MarketMentorOverlay extends OverlayPanel
{
    private final MarketMentorPlugin plugin;

    @Inject
    public MarketMentorOverlay(MarketMentorPlugin plugin)
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
                .text("Market Mentor")
                .color(new Color(119, 221, 119))
                .build());

        panelComponent.getChildren().add(LineComponent.builder().left("Runtime").right(plugin.getRuntimeText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Status").right(plugin.getStatusText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Slots").right(plugin.getSlotText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Coins").right(plugin.getCoinsText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Current").right(plugin.getCurrentSuggestionText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Best").right(plugin.getBestSuggestionText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("P/L").right(plugin.getProfitText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Avg GP/hr").right(plugin.getAverageGpPerHourText()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Items flipped").right(String.valueOf(plugin.getItemsFlipped())).build());
        panelComponent.getChildren().add(LineComponent.builder().left("API refresh").right(plugin.getNextApiRefreshText()).build());

        return super.render(graphics);
    }
}
