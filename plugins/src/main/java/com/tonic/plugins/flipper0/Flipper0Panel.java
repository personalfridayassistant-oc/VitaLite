package com.tonic.plugins.flipper0;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;

public class Flipper0Panel extends PluginPanel
{
    public interface Actions
    {
        void skipCurrent();
        void blacklistCurrent();
    }

    private final ItemManager itemManager;
    private final JLabel statusLabel = new JLabel("Status: idle");
    private final JLabel coinsLabel = new JLabel("Coins: 0");
    private final JLabel slotsLabel = new JLabel("Slots: 0/0");
    private final JLabel iconLabel = new JLabel();
    private final JLabel suggestionLabel = new JLabel("No suggestion");
    private final JPanel listPanel = new JPanel();

    @Inject
    public Flipper0Panel(ItemManager itemManager)
    {
        this.itemManager = itemManager;

        setLayout(new BorderLayout(0, 8));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        coinsLabel.setFont(FontManager.getRunescapeSmallFont());
        slotsLabel.setFont(FontManager.getRunescapeSmallFont());
        header.add(statusLabel);
        header.add(coinsLabel);
        header.add(slotsLabel);

        JPanel currentCard = new JPanel(new BorderLayout(8, 8));
        currentCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        currentCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
                new EmptyBorder(8, 8, 8, 8)));
        suggestionLabel.setFont(FontManager.getRunescapeBoldFont());
        currentCard.add(iconLabel, BorderLayout.WEST);
        currentCard.add(suggestionLabel, BorderLayout.CENTER);

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(ColorScheme.DARK_GRAY_COLOR);
        top.add(header);
        top.add(Box.createRigidArea(new Dimension(0, 8)));
        top.add(currentCard);

        add(top, BorderLayout.NORTH);
        add(listPanel, BorderLayout.CENTER);
    }

    public void refresh(String status, String coins, String slots, Flipper0Plugin.Suggestion current,
                        List<Flipper0Plugin.Suggestion> top, Actions actions)
    {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: " + status);
            coinsLabel.setText("Coins: " + coins);
            slotsLabel.setText("Slots: " + slots);

            if (current != null)
            {
                AsyncBufferedImage image = itemManager.getImage(current.itemId, 1, false);
                image.addTo(iconLabel);
                suggestionLabel.setText(current.name + " (ROI " + String.format("%.2f%%", current.roiPct) + ")");
            }
            else
            {
                iconLabel.setIcon(null);
                suggestionLabel.setText("No suggestion");
            }

            listPanel.removeAll();
            if (top == null || top.isEmpty())
            {
                listPanel.add(new JLabel("No valid suggestions"));
            }
            else
            {
                int max = Math.min(5, top.size());
                for (int i = 0; i < max; i++)
                {
                    Flipper0Plugin.Suggestion s = top.get(i);
                    listPanel.add(buildSuggestionRow(s, i == 0, actions));
                    listPanel.add(Box.createRigidArea(new Dimension(0, 5)));
                }
            }
            listPanel.revalidate();
            listPanel.repaint();
        });
    }

    private JPanel buildSuggestionRow(Flipper0Plugin.Suggestion s, boolean actionable, Actions actions)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
                new EmptyBorder(6, 6, 6, 6)));

        JLabel item = new JLabel(s.name + " | buy " + s.buyPrice + " sell " + s.sellPrice + " | vol " + s.minVolume);
        item.setFont(FontManager.getRunescapeSmallFont());
        row.add(item, BorderLayout.CENTER);

        if (actionable)
        {
            JPanel buttons = new JPanel();
            buttons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            JButton skip = new JButton("Skip");
            skip.addActionListener(e -> actions.skipCurrent());
            JButton blacklist = new JButton("Blacklist");
            blacklist.addActionListener(e -> actions.blacklistCurrent());
            buttons.add(skip);
            buttons.add(blacklist);
            row.add(buttons, BorderLayout.EAST);
        }
        return row;
    }
}
