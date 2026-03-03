package com.tonic.plugins.combatprayer;

import com.google.inject.Inject;
import com.tonic.api.widgets.PrayerAPI;
import com.tonic.data.wrappers.NpcEx;
import com.tonic.services.GameManager;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Combat Prayer Plugin
 * Automatically manages prayers based on combat situations in OSRS.
 */
@PluginDescriptor(
        name = "Combat Prayer",
        description = "Automatically manages prayers based on combat situations",
        enabledByDefault = true
)
public class CombatPrayerPlugin extends Plugin
{
    private static final Logger logger = LoggerFactory.getLogger(CombatPrayerPlugin.class);

    private static final BufferedImage pluginIcon;
    
    static
    {
        BufferedImage icon = null;
        try
        {
            icon = ImageUtil.loadImageResource(CombatPrayerPlugin.class, "prayer_icon.png");
        }
        catch (Exception e)
        {
            // Use default icon if loading fails
        }
        pluginIcon = icon;
    }

    @Inject
    private Client client;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ClientToolbar clientToolbar;

    private NavigationButton navigationButton;
    private PrayerManager prayerManager;

    @Override
    protected void startUp() throws Exception
    {
        prayerManager = new PrayerManager(client, configManager);
        
        navigationButton = NavigationButton.builder()
                .panel(prayerManager.getPanel())
                .priority(-1000)
                .icon(pluginIcon != null ? pluginIcon : createDefaultIcon())
                .tooltip("Combat Prayer")
                .build();

        clientToolbar.addNavigation(navigationButton);
    }

    @Override
    protected void shutDown() throws Exception
    {
        if (prayerManager != null)
        {
            prayerManager.shutdown();
        }
        
        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
            navigationButton = null;
        }
    }

    @Subscribe
    private void onGameTick(GameTick event)
    {
        if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            return;
        }

        prayerManager.update();
    }

    @Subscribe
    private void onChatMessage(ChatMessage event)
    {
        prayerManager.onChatMessage(event);
    }

    private BufferedImage createDefaultIcon()
    {
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(new Color(0, 100, 0)); // Dark green background
        g.fillRect(0, 0, 16, 16);
        g.setColor(Color.WHITE);
        g.drawString("P", 4, 12);
        g.dispose();
        return icon;
    }
}
