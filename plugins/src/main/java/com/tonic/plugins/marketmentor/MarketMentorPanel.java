package com.tonic.plugins.marketmentor;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

public class MarketMentorPanel extends PluginPanel
{
    private final ItemManager itemManager;
    private final JLabel statusLabel = new JLabel("Status: Idle");
    private final JLabel profitLabel = new JLabel("P/L: 0");
    private final JLabel gpPerHourLabel = new JLabel("Avg GP/hr: 0");
    private final JLabel itemsFlippedLabel = new JLabel("Items flipped: 0");
    private final JPanel offersContainer = new JPanel();

    @Inject
    public MarketMentorPanel(ItemManager itemManager)
    {
        this.itemManager = itemManager;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel title = new JLabel("Market Mentor");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(ColorScheme.BRAND_ORANGE);
        title.setAlignmentX(LEFT_ALIGNMENT);

        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        profitLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gpPerHourLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        itemsFlippedLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        header.add(title);
        header.add(Box.createRigidArea(new Dimension(0, 6)));
        header.add(statusLabel);
        header.add(profitLabel);
        header.add(gpPerHourLabel);
        header.add(itemsFlippedLabel);

        offersContainer.setLayout(new BoxLayout(offersContainer, BoxLayout.Y_AXIS));
        offersContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JScrollPane scrollPane = new JScrollPane(offersContainer);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        add(header, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    public void refresh(String status, String profitText, String gpPerHourText, int itemsFlipped, List<MarketMentorPlugin.PanelOffer> offers)
    {
        List<MarketMentorPlugin.PanelOffer> safeOffers = offers == null ? new ArrayList<>() : offers;

        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: " + status);
            profitLabel.setText("P/L: " + profitText);
            gpPerHourLabel.setText("Avg GP/hr: " + gpPerHourText);
            itemsFlippedLabel.setText("Items flipped: " + itemsFlipped);

            offersContainer.removeAll();
            if (safeOffers.isEmpty())
            {
                JLabel empty = new JLabel("No active suggestions yet");
                empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                empty.setHorizontalAlignment(SwingConstants.CENTER);
                offersContainer.add(empty);
            }
            else
            {
                for (MarketMentorPlugin.PanelOffer offer : safeOffers)
                {
                    offersContainer.add(createOfferRow(offer));
                    offersContainer.add(Box.createRigidArea(new Dimension(0, 6)));
                }
            }

            offersContainer.revalidate();
            offersContainer.repaint();
        });
    }

    private JPanel createOfferRow(MarketMentorPlugin.PanelOffer offer)
    {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(new EmptyBorder(6, 6, 6, 6));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

        JLabel iconLabel = new JLabel();
        AsyncBufferedImage img = itemManager.getImage(offer.getItemId(), 1, false);
        img.addTo(iconLabel);
        row.add(iconLabel, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JLabel name = new JLabel(offer.getName());
        name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        name.setFont(FontManager.getRunescapeBoldFont());

        JLabel details = new JLabel(String.format("ROI %s | Vol %s | Spread %s", offer.getRoiText(), offer.getVolumeText(), offer.getSpreadText()));
        details.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        details.setFont(FontManager.getRunescapeSmallFont());

        text.add(name);
        text.add(details);
        row.add(text, BorderLayout.CENTER);

        return row;
    }
}
