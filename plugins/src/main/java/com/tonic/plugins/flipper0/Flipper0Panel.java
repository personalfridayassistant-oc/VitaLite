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
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;

public class Flipper0Panel extends PluginPanel
{
    public interface Actions
    {
        void skipCurrent();
        void blacklistCurrent();
    }

    private final ItemManager itemManager;

    private final JLabel statusLabel = new JLabel("Waiting for data");
    private final JLabel coinsLabel = new JLabel("Coins: 0");
    private final JLabel slotsLabel = new JLabel("GE Slots: 0/0");
    private final JLabel gphrLabel = new JLabel("GP/hr: 0");
    private final JLabel apiLabel = new JLabel("API: Unknown");

    private final JLabel debugLabel = new JLabel("Last fetch: n/a");

    private final JLabel currentIconLabel = new JLabel();
    private final JLabel currentTitleLabel = new JLabel("No recommendation yet");
    private final JLabel currentMetaLabel = new JLabel("-");

    private final JButton skipButton = new JButton("Skip");
    private final JButton blacklistButton = new JButton("Blacklist");

    private final JPanel listContainer = new JPanel();
    private Actions currentActions;

    @Inject
    public Flipper0Panel(ItemManager itemManager)
    {
        this.itemManager = itemManager;

        setLayout(new BorderLayout(0, 8));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        add(buildHeaderCard(), BorderLayout.NORTH);

        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
        listContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JScrollPane scroll = new JScrollPane(listContainer);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        add(scroll, BorderLayout.CENTER);

        skipButton.addActionListener(e -> { if (currentActions != null) currentActions.skipCurrent(); });
        blacklistButton.addActionListener(e -> { if (currentActions != null) currentActions.blacklistCurrent(); });
    }

    private JPanel buildHeaderCard()
    {
        JPanel root = card(new BorderLayout(0, 8));

        JPanel stats = new JPanel(new GridLayout(3, 2, 6, 4));
        stats.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        styleInfo(statusLabel);
        styleInfo(coinsLabel);
        styleInfo(slotsLabel);
        styleInfo(gphrLabel);
        styleInfo(apiLabel);
        styleInfo(debugLabel);
        centerLabel(statusLabel);
        centerLabel(coinsLabel);
        centerLabel(slotsLabel);
        centerLabel(gphrLabel);
        centerLabel(apiLabel);
        centerLabel(debugLabel);
        stats.add(statusLabel);
        stats.add(coinsLabel);
        stats.add(slotsLabel);
        stats.add(gphrLabel);
        stats.add(apiLabel);
        stats.add(debugLabel);

        JPanel current = new JPanel(new BorderLayout(8, 0));
        current.setOpaque(false);
        currentIconLabel.setPreferredSize(new Dimension(44, 44));
        currentIconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        current.add(currentIconLabel, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        currentTitleLabel.setFont(FontManager.getRunescapeBoldFont());
        currentTitleLabel.setForeground(Color.WHITE);
        currentTitleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        currentMetaLabel.setFont(FontManager.getRunescapeSmallFont());
        currentMetaLabel.setForeground(new Color(188, 196, 206));
        currentMetaLabel.setHorizontalAlignment(SwingConstants.CENTER);
        text.add(currentTitleLabel);
        text.add(currentMetaLabel);
        text.setAlignmentX(Component.CENTER_ALIGNMENT);
        current.add(text, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        actions.setOpaque(false);
        skipButton.setFocusable(false);
        blacklistButton.setFocusable(false);
        actions.add(skipButton);
        actions.add(blacklistButton);

        root.add(stats, BorderLayout.NORTH);
        root.add(current, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);

        return root;
    }

    public void refresh(String status, String coins, String slots, String gpPerHour, String apiStatus, String debugInfo,
                        Flipper0Plugin.Suggestion current, List<Flipper0Plugin.Suggestion> top, Actions actions)
    {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(status);
            coinsLabel.setText("Coins: " + coins);
            slotsLabel.setText("GE Slots: " + slots);
            gphrLabel.setText("GP/hr: " + gpPerHour);
            apiLabel.setText("API: " + apiStatus);
            debugLabel.setText("Last fetch: " + debugInfo);

            currentActions = actions;

            if (current != null)
            {
                AsyncBufferedImage image = itemManager.getImage(current.itemId, 1, false);
                image.addTo(currentIconLabel);
                currentTitleLabel.setText(current.name);
                currentMetaLabel.setText(String.format("Buy %,d | Sell %,d | ROI %.2f%% | Vol %,d", current.buyPrice, current.sellPrice, current.roiPct, current.minVolume));
            }
            else
            {
                currentIconLabel.setIcon(null);
                currentTitleLabel.setText("No recommendation yet");
                currentMetaLabel.setText("Waiting for a valid API suggestion");
            }

            listContainer.removeAll();
            if (top == null || top.isEmpty())
            {
                JLabel empty = new JLabel("No valid suggestions available", SwingConstants.CENTER);
                empty.setForeground(new Color(188, 196, 206));
                empty.setAlignmentX(Component.CENTER_ALIGNMENT);
                listContainer.add(Box.createVerticalGlue());
                listContainer.add(empty);
                listContainer.add(Box.createVerticalGlue());
            }
            else
            {
                int count = Math.min(7, top.size());
                for (int i = 0; i < count; i++)
                {
                    listContainer.add(buildSuggestionRow(top.get(i), i + 1));
                    listContainer.add(Box.createRigidArea(new Dimension(0, 6)));
                }
            }

            listContainer.revalidate();
            listContainer.repaint();
        });
    }

    private JPanel buildSuggestionRow(Flipper0Plugin.Suggestion s, int rank)
    {
        JPanel row = card(new BorderLayout(8, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 86));

        JLabel icon = new JLabel();
        AsyncBufferedImage image = itemManager.getImage(s.itemId, 1, false);
        image.addTo(icon);
        row.add(icon, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("#" + rank + " " + s.name, SwingConstants.CENTER);
        title.setForeground(Color.WHITE);
        title.setFont(FontManager.getRunescapeBoldFont());

        JLabel detail = new JLabel(String.format("Buy %,d | Sell %,d | ROI %.2f%% | Vol %,d", s.buyPrice, s.sellPrice, s.roiPct, s.minVolume), SwingConstants.CENTER);
        detail.setForeground(new Color(188, 196, 206));
        detail.setFont(FontManager.getRunescapeSmallFont());

        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        detail.setAlignmentX(Component.CENTER_ALIGNMENT);
        text.add(title);
        text.add(detail);
        row.add(text, BorderLayout.CENTER);

        return row;
    }

    private JPanel card(BorderLayout layout)
    {
        JPanel panel = new JPanel(layout);
        panel.setBackground(new Color(38, 43, 51));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(86, 97, 112), 1, true),
                new EmptyBorder(10, 10, 10, 10)));
        return panel;
    }

    private void styleInfo(JLabel label)
    {
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(new Color(210, 216, 224));
    }

    private void centerLabel(JLabel label)
    {
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
    }
}
