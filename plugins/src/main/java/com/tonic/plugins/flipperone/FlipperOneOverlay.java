package com.tonic.plugins.flipperone;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

public class FlipperOneOverlay extends Overlay
{
    private final FlipperOnePlugin plugin;
    private final ItemManager itemManager;

    @Inject
    public FlipperOneOverlay(FlipperOnePlugin plugin, ItemManager itemManager)
    {
        this.plugin = plugin;
        this.itemManager = itemManager;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!plugin.shouldRenderOverlay())
        {
            return null;
        }

        int width = 250;
        int height = 198;
        int x = 10;
        int y = Math.max(28, 10 + plugin.getOverlayOffsetY());

        graphics.setColor(new Color(18, 21, 25, 220));
        graphics.fillRoundRect(x, y, width, height, 12, 12);
        graphics.setColor(new Color(78, 88, 103, 220));
        graphics.drawRoundRect(x, y, width, height, 12, 12);

        graphics.setColor(new Color(102, 216, 136));
        graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 13f));
        graphics.drawString("FlipperOne", x + 10, y + 18);

        graphics.setColor(Color.WHITE);
        graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 12f));

        int lineY = y + 36;
        graphics.drawString("State: " + plugin.getStatusText(), x + 10, lineY);
        lineY += 16;
        graphics.drawString("GP/hr: " + plugin.getGpPerHourText(), x + 10, lineY);
        lineY += 16;
        graphics.drawString("Slots: " + plugin.getSlotsText(), x + 10, lineY);
        lineY += 16;
        graphics.drawString("API: " + plugin.getApiHealthText(), x + 10, lineY);

        FlipperOnePlugin.Suggestion current = plugin.getCurrentSuggestion();
        FlipperOnePlugin.Suggestion next = plugin.getNextSuggestion();

        drawSuggestionBlock(graphics, x + 10, y + 102, "Current", current);
        drawSuggestionBlock(graphics, x + 128, y + 102, "Next", next);

        return new Dimension(width, height);
    }

    private void drawSuggestionBlock(Graphics2D graphics, int x, int y, String title, FlipperOnePlugin.Suggestion s)
    {
        graphics.setColor(new Color(45, 52, 60, 220));
        graphics.fillRoundRect(x, y, 110, 84, 8, 8);
        graphics.setColor(new Color(90, 100, 112));
        graphics.drawRoundRect(x, y, 110, 84, 8, 8);

        graphics.setColor(new Color(189, 196, 206));
        graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 11f));
        graphics.drawString(title, x + 6, y + 13);

        if (s == null)
        {
            graphics.setColor(Color.LIGHT_GRAY);
            graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 10f));
            graphics.drawString("None", x + 6, y + 30);
            return;
        }

        BufferedImage image = itemManager.getImage(s.itemId, 1, false);
        if (image != null)
        {
            graphics.drawImage(image, x + 6, y + 18, 28, 28, null);
        }

        graphics.setColor(Color.WHITE);
        graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 10f));
        graphics.setClip(new Rectangle(x + 38, y + 17, 68, 30));
        graphics.drawString(s.name, x + 38, y + 28);
        graphics.setClip(null);
        graphics.drawString("ROI " + String.format("%.2f%%", s.roiPct), x + 6, y + 56);
        graphics.drawString("Vol " + s.minVolume, x + 6, y + 70);
    }
}
