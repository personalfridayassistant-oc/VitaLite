package com.tonic.plugins.doomassistant;

import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import java.awt.*;

public class DoomAssistantPanel extends PluginPanel
{
    private final DoomAssistantPlugin plugin;

    private final JLabel phaseValue = valueLabel();
    private final JLabel styleValue = valueLabel();
    private final JLabel tickValue = valueLabel();
    private final JLabel hpValue = valueLabel();
    private final JLabel prayerValue = valueLabel();
    private final JLabel markerValue = valueLabel();
    private final JTextArea calloutValue = new JTextArea();

    public DoomAssistantPanel(DoomAssistantPlugin plugin)
    {
        this.plugin = plugin;
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel stats = new JPanel(new GridLayout(0, 2, 8, 8));
        stats.add(label("Phase"));
        stats.add(phaseValue);
        stats.add(label("Last style"));
        stats.add(styleValue);
        stats.add(label("Elapsed ticks"));
        stats.add(tickValue);
        stats.add(label("HP %"));
        stats.add(hpValue);
        stats.add(label("Prayer"));
        stats.add(prayerValue);
        stats.add(label("Mechanic tiles"));
        stats.add(markerValue);

        calloutValue.setLineWrap(true);
        calloutValue.setWrapStyleWord(true);
        calloutValue.setEditable(false);
        calloutValue.setBackground(new Color(35, 35, 35));
        calloutValue.setForeground(Color.WHITE);
        calloutValue.setBorder(BorderFactory.createTitledBorder("Current callout"));

        add(stats, BorderLayout.NORTH);
        add(calloutValue, BorderLayout.CENTER);

        Timer timer = new Timer(350, e -> refresh());
        timer.start();
    }

    private void refresh()
    {
        phaseValue.setText(plugin.getCurrentPhase());
        styleValue.setText(plugin.getLastStyleText());
        tickValue.setText(String.valueOf(plugin.getTicksSinceStart()));
        hpValue.setText(String.valueOf(plugin.getCurrentHpPercent()));
        prayerValue.setText(String.valueOf(plugin.getCurrentPrayer()));
        markerValue.setText(String.valueOf(plugin.getMechanicMarkers().size()));
        calloutValue.setText(plugin.getCurrentCallout());
    }

    private JLabel label(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(180, 180, 180));
        return label;
    }

    private static JLabel valueLabel()
    {
        JLabel label = new JLabel("-");
        label.setForeground(Color.WHITE);
        return label;
    }
}
