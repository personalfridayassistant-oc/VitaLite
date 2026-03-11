package com.tonic.plugins.doomassistant;

import net.runelite.api.Client;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.components.LineComponent;

import javax.inject.Inject;
import java.awt.*;
import java.util.List;

public class DoomAssistantOverlay extends OverlayPanel
{
    private final DoomAssistantPlugin plugin;
    private final Client client;

    @Inject
    public DoomAssistantOverlay(DoomAssistantPlugin plugin, Client client)
    {
        this.plugin = plugin;
        this.client = client;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        panelComponent.getChildren().clear();

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Doom Assistant")
                .right(plugin.getCurrentPhase())
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Style")
                .right(plugin.getLastStyleText())
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Ticks")
                .right(String.valueOf(plugin.getTicksSinceStart()))
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("HP%")
                .right(String.valueOf(plugin.getCurrentHpPercent()))
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Prayer")
                .right(String.valueOf(plugin.getCurrentPrayer()))
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Callout")
                .right(plugin.getCurrentCallout())
                .build());

        if (plugin.showMechanicOverlays())
        {
            renderMechanicTiles(graphics, plugin.getMechanicMarkers());
        }

        return super.render(graphics);
    }

    private void renderMechanicTiles(Graphics2D graphics, List<DoomAssistantPlugin.MechanicTileMarker> markers)
    {
        for (DoomAssistantPlugin.MechanicTileMarker marker : markers)
        {
            WorldPoint point = marker.getWorldPoint();
            if (point == null || point.getPlane() != client.getPlane())
            {
                continue;
            }

            LocalPoint lp = LocalPoint.fromWorld(client, point);
            if (lp == null)
            {
                continue;
            }

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null)
            {
                continue;
            }

            DoomAssistantPlugin.MechanicType type = marker.getType();
            OverlayUtil.renderPolygon(graphics, poly, type.getOutline(), type.getFill(), new BasicStroke(2f));

            Point textPoint = Perspective.getCanvasTextLocation(client, graphics, lp, type.getLabel(), 10);
            if (textPoint != null)
            {
                OverlayUtil.renderTextLocation(graphics, textPoint, type.getLabel(), type.getOutline());
            }
        }
    }
}
