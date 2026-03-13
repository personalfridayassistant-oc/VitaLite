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
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

public class MarketMentorPanel extends PluginPanel
{
    private final ItemManager itemManager;
    private final JLabel statusLabel = new JLabel("Status: Idle");
    private final JLabel profitLabel = new JLabel("P/L: 0");
    private final JLabel gpPerHourLabel = new JLabel("Avg GP/hr: 0");
    private final JLabel itemsFlippedLabel = new JLabel("Items flipped: 0");
    private final JLabel currentItemIcon = new JLabel();
    private final JLabel currentItemLabel = new JLabel("Current Trade: None");
    private final JPanel offersContainer = new JPanel();

    @Inject
    public MarketMentorPanel(ItemManager itemManager)
    {
        this.itemManager = itemManager;

        setLayout(new BorderLayout(0, 8));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel headerCard = buildHeaderCard();
        JPanel currentTradeCard = buildCurrentTradeCard();

        offersContainer.setLayout(new BoxLayout(offersContainer, BoxLayout.Y_AXIS));
        offersContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JScrollPane scrollPane = new JScrollPane(offersContainer);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(ColorScheme.DARK_GRAY_COLOR);
        top.add(headerCard);
        top.add(Box.createRigidArea(new Dimension(0, 8)));
        top.add(currentTradeCard);

        add(top, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    private JPanel buildHeaderCard()
    {
        JPanel card = new JPanel(new GridLayout(2, 2, 8, 4));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(new CompoundCardBorder());

        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        profitLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        gpPerHourLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        itemsFlippedLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        profitLabel.setFont(FontManager.getRunescapeSmallFont());
        gpPerHourLabel.setFont(FontManager.getRunescapeSmallFont());
        itemsFlippedLabel.setFont(FontManager.getRunescapeSmallFont());

        card.add(statusLabel);
        card.add(profitLabel);
        card.add(gpPerHourLabel);
        card.add(itemsFlippedLabel);
        return card;
    }

    private JPanel buildCurrentTradeCard()
    {
        JPanel card = new JPanel(new BorderLayout(8, 0));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(new CompoundCardBorder());

        currentItemIcon.setHorizontalAlignment(SwingConstants.CENTER);
        currentItemLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        currentItemLabel.setFont(FontManager.getRunescapeBoldFont());

        card.add(currentItemIcon, BorderLayout.WEST);
        card.add(currentItemLabel, BorderLayout.CENTER);
        return card;
    }

    public void refresh(String status, String profitText, String gpPerHourText, int itemsFlipped,
                        int tradedItemId, String tradedItemName, List<MarketMentorPlugin.PanelOffer> offers)
    {
        List<MarketMentorPlugin.PanelOffer> safeOffers = offers == null ? new ArrayList<>() : offers;

        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: " + status);
            profitLabel.setText("P/L: " + profitText);
            gpPerHourLabel.setText("Avg GP/hr: " + gpPerHourText);
            itemsFlippedLabel.setText("Items flipped: " + itemsFlipped);

            if (tradedItemId > 0)
            {
                AsyncBufferedImage img = itemManager.getImage(tradedItemId, 1, false);
                img.addTo(currentItemIcon);
                currentItemLabel.setText("Current Trade: " + tradedItemName);
            }
            else
            {
                currentItemIcon.setIcon(null);
                currentItemLabel.setText("Current Trade: None");
            }

            offersContainer.removeAll();
            if (safeOffers.isEmpty())
            {
                JLabel empty = new JLabel("No valid opportunities found");
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
        row.setBorder(new CompoundCardBorder());
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));

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

    private static class CompoundCardBorder extends EmptyBorder
    {
        private CompoundCardBorder()
        {
            super(8, 8, 8, 8);
        }

        @Override
        public void paintBorder(java.awt.Component c, java.awt.Graphics g, int x, int y, int width, int height)
        {
            super.paintBorder(c, g, x, y, width, height);
            new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1, true).paintBorder(c, g, x, y, width, height);
        }
    }
}
