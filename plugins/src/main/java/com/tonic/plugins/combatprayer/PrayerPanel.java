package com.tonic.plugins.combatprayer;

import com.tonic.api.widgets.PrayerAPI;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

/**
 * Main panel for the Combat Prayer plugin.
 */
public class PrayerPanel extends PluginPanel
{
    private final PrayerManager manager;
    private JLabel combatStateLabel;
    private JLabel currentTargetLabel;
    private JLabel combatStyleLabel;
    private final DefaultListModel<String> activePrayersModel;

    public PrayerPanel(PrayerManager manager)
    {
        this.manager = manager;
        this.activePrayersModel = new DefaultListModel<>();

        setLayout(new BorderLayout(0, 8));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(250, 300));

        initializeUi();
    }

    private void initializeUi()
    {
        JPanel statusPanel = createCard("Combat State");
        combatStateLabel = createValueLabel("State: Idle");
        statusPanel.add(combatStateLabel, BorderLayout.CENTER);

        JPanel stylePanel = createCard("Combat Style");
        combatStyleLabel = createValueLabel("Style: Unknown");
        stylePanel.add(combatStyleLabel, BorderLayout.CENTER);

        JPanel targetPanel = createCard("Current Target");
        currentTargetLabel = createValueLabel("Target: None");
        targetPanel.add(currentTargetLabel, BorderLayout.CENTER);

        JPanel activePrayersPanel = createCard("Active Prayers");
        JList<String> activePrayersList = new JList<>(activePrayersModel);
        activePrayersList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        activePrayersList.setForeground(Color.WHITE);
        activePrayersList.setSelectionBackground(new Color(100, 100, 100));
        activePrayersList.setVisibleRowCount(8);
        JScrollPane prayerScrollPane = new JScrollPane(activePrayersList);
        prayerScrollPane.setBorder(BorderFactory.createEmptyBorder());
        activePrayersPanel.add(prayerScrollPane, BorderLayout.CENTER);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));
        centerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel topPanel = new JPanel(new BorderLayout(0, 8));
        topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        topPanel.add(statusPanel, BorderLayout.NORTH);
        topPanel.add(stylePanel, BorderLayout.CENTER);
        topPanel.add(targetPanel, BorderLayout.SOUTH);

        centerPanel.add(topPanel, BorderLayout.NORTH);
        centerPanel.add(activePrayersPanel, BorderLayout.CENTER);

        add(centerPanel, BorderLayout.CENTER);
    }

    public void update()
    {
        boolean inCombat = manager.isInCombat();
        combatStateLabel.setText("State: " + (inCombat ? "Combat" : "Idle"));
        combatStateLabel.setForeground(inCombat ? Color.ORANGE : Color.WHITE);

        String styleText = "Unknown";
        if (manager.isUsingRanged())
        {
            styleText = "Ranged";
            combatStyleLabel.setForeground(new Color(0, 200, 0));
        }
        else if (manager.isUsingMagic())
        {
            styleText = "Magic";
            combatStyleLabel.setForeground(new Color(100, 100, 255));
        }
        else if (manager.isUsingMelee())
        {
            styleText = "Melee";
            combatStyleLabel.setForeground(new Color(255, 100, 100));
        }
        else
        {
            combatStyleLabel.setForeground(Color.WHITE);
        }
        combatStyleLabel.setText("Style: " + styleText);

        if (manager.getCurrentTarget() != null && manager.getCurrentTarget().getComposition() != null)
        {
            currentTargetLabel.setText("Target: " + manager.getCurrentTarget().getComposition().getName());
            currentTargetLabel.setForeground(Color.WHITE);
        }
        else
        {
            currentTargetLabel.setText("Target: None");
            currentTargetLabel.setForeground(new Color(150, 150, 150));
        }

        activePrayersModel.clear();
        PrayerAPI activePrayer = manager.getActivePrayer();
        if (activePrayer != null && activePrayer.isActive())
        {
            activePrayersModel.addElement("Overhead: " + activePrayer.name());
        }

        if (activePrayersModel.isEmpty())
        {
            activePrayersModel.addElement("No active prayers");
        }
    }

    private static JPanel createCard(String title)
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(100, 100, 100)),
                title,
                JLabel.CENTER,
                JLabel.TOP,
                new Font("SansSerif", Font.BOLD, 12),
                Color.WHITE
        ));
        return panel;
    }

    private static JLabel createValueLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setForeground(Color.WHITE);
        label.setFont(FontManager.getRunescapeFont());
        return label;
    }
}
