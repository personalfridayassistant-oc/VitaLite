package com.tonic.plugins.combatprayer;

import javax.swing.*;
import java.awt.*;

/**
 * Main panel for the Combat Prayer plugin.
 */
public class PrayerPanel extends JPanel
{
    private final PrayerManager manager;
    private JLabel combatStateLabel;
    private JLabel currentTargetLabel;
    private JLabel activePrayersLabel;
    private JLabel combatStyleLabel;
    private final DefaultListModel<String> activePrayersModel;

    public PrayerPanel(PrayerManager manager)
    {
        this.manager = manager;
        this.activePrayersModel = new DefaultListModel<>();
        
        setLayout(new BorderLayout());
        setBackground(new Color(60, 60, 60));
        setPreferredSize(new Dimension(250, 300));
        
        initializeUI();
    }

    private void initializeUI()
    {
        // Combat State Panel
        JPanel combatStatePanel = new JPanel();
        combatStatePanel.setLayout(new BorderLayout());
        combatStatePanel.setBackground(new Color(50, 50, 50));
        combatStatePanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(100, 100, 100)), 
            "Combat State", 
            JLabel.CENTER, 
            JLabel.TOP,
            new Font("SansSerif", Font.BOLD, 12),
            Color.WHITE
        ));
        
        combatStateLabel = new JLabel("State: Idle");
        combatStateLabel.setForeground(Color.WHITE);
        combatStateLabel.setHorizontalAlignment(SwingConstants.CENTER);
        combatStatePanel.add(combatStateLabel, BorderLayout.CENTER);
        
        // Combat Style Panel
        JPanel combatStylePanel = new JPanel();
        combatStylePanel.setLayout(new BorderLayout());
        combatStylePanel.setBackground(new Color(50, 50, 50));
        combatStylePanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(100, 100, 100)), 
            "Combat Style", 
            JLabel.CENTER, 
            JLabel.TOP,
            new Font("SansSerif", Font.BOLD, 12),
            Color.WHITE
        ));
        
        combatStyleLabel = new JLabel("Style: Melee");
        combatStyleLabel.setForeground(Color.WHITE);
        combatStyleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        combatStylePanel.add(combatStyleLabel, BorderLayout.CENTER);
        
        // Current Target Panel
        JPanel currentTargetPanel = new JPanel();
        currentTargetPanel.setLayout(new BorderLayout());
        currentTargetPanel.setBackground(new Color(50, 50, 50));
        currentTargetPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(100, 100, 100)), 
            "Current Target", 
            JLabel.CENTER, 
            JLabel.TOP,
            new Font("SansSerif", Font.BOLD, 12),
            Color.WHITE
        ));
        
        currentTargetLabel = new JLabel("Target: None");
        currentTargetLabel.setForeground(Color.WHITE);
        currentTargetLabel.setHorizontalAlignment(SwingConstants.CENTER);
        currentTargetPanel.add(currentTargetLabel, BorderLayout.CENTER);
        
        // Active Prayers Panel
        JPanel activePrayersPanel = new JPanel();
        activePrayersPanel.setLayout(new BorderLayout());
        activePrayersPanel.setBackground(new Color(50, 50, 50));
        activePrayersPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(100, 100, 100)), 
            "Active Prayers", 
            JLabel.CENTER, 
            JLabel.TOP,
            new Font("SansSerif", Font.BOLD, 12),
            Color.WHITE
        ));
        
        JList<String> activePrayersList = new JList<>(activePrayersModel);
        activePrayersList.setBackground(new Color(70, 70, 70));
        activePrayersList.setForeground(Color.WHITE);
        activePrayersList.setSelectionBackground(new Color(100, 100, 100));
        activePrayersList.setVisibleRowCount(8);
        JScrollPane prayerScrollPane = new JScrollPane(activePrayersList);
        prayerScrollPane.setBorder(BorderFactory.createEmptyBorder());
        activePrayersPanel.add(prayerScrollPane, BorderLayout.CENTER);
        
        // Add all panels to main panel
        add(combatStatePanel, BorderLayout.NORTH);
        add(combatStylePanel, BorderLayout.CENTER);
        add(currentTargetPanel, BorderLayout.SOUTH);
        add(activePrayersPanel, BorderLayout.EAST);
    }

    public void update()
    {
        // Update combat state
        String stateText = manager.isInCombat() ? "Combat" : "Idle";
        combatStateLabel.setText("State: " + stateText);
        combatStateLabel.setForeground(manager.isInCombat() ? Color.ORANGE : Color.WHITE);
        
        // Update combat style
        String styleText = "Unknown";
        if (manager.isUsingRanged())
        {
            styleText = "Ranged";
            combatStyleLabel.setForeground(new Color(0, 200, 0)); // Green
        }
        else if (manager.isUsingMagic())
        {
            styleText = "Magic";
            combatStyleLabel.setForeground(new Color(100, 100, 255)); // Blue
        }
        else if (manager.isUsingMelee())
        {
            styleText = "Melee";
            combatStyleLabel.setForeground(new Color(255, 100, 100)); // Red
        }
        else
        {
            combatStyleLabel.setForeground(Color.WHITE);
        }
        combatStyleLabel.setText("Style: " + styleText);
        
        // Update current target
        if (manager.getCurrentTarget() != null)
        {
            currentTargetLabel.setText("Target: " + manager.getCurrentTarget().getComposition().getName());
            currentTargetLabel.setForeground(Color.WHITE);
        }
        else
        {
            currentTargetLabel.setText("Target: None");
            currentTargetLabel.setForeground(new Color(150, 150, 150));
        }
        
        // Update active prayers list
        activePrayersModel.clear();
        
        if (manager.getCurrentMagicPrayer() != null && manager.getCurrentMagicPrayer().isActive())
        {
            activePrayersModel.addElement("Magic: " + manager.getCurrentMagicPrayer().name());
        }
        
        if (manager.getCurrentRangedPrayer() != null && manager.getCurrentRangedPrayer().isActive())
        {
            activePrayersModel.addElement("Ranged: " + manager.getCurrentRangedPrayer().name());
        }
        
        if (manager.getCurrentMeleePrayer() != null && manager.getCurrentMeleePrayer().isActive())
        {
            activePrayersModel.addElement("Melee: " + manager.getCurrentMeleePrayer().name());
        }
        
        if (manager.getCurrentOverheadPrayer() != null && manager.getCurrentOverheadPrayer().isActive())
        {
            activePrayersModel.addElement("Overhead: " + manager.getCurrentOverheadPrayer().name());
        }
        
        if (activePrayersModel.isEmpty())
        {
            activePrayersModel.addElement("No active prayers");
        }
    }
    
    public boolean isUsingRanged()
    {
        return manager.isUsingRanged();
    }
    
    public boolean isUsingMagic()
    {
        return manager.isUsingMagic();
    }
    
    public boolean isUsingMelee()
    {
        return manager.isUsingMelee();
    }
    
    public com.tonic.data.wrappers.NpcEx getCurrentTarget()
    {
        return manager.getCurrentTarget();
    }
}
