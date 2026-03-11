package com.tonic.plugins.doomassistant;

import com.google.inject.Provides;
import com.tonic.api.entities.NpcAPI;
import com.tonic.api.entities.TileObjectAPI;
import com.tonic.api.game.SkillAPI;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.api.widgets.PrayerAPI;
import com.tonic.data.wrappers.ItemEx;
import com.tonic.data.wrappers.NpcEx;
import com.tonic.data.wrappers.TileObjectEx;
import com.tonic.queries.NpcQuery;
import com.tonic.util.MessageUtil;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.Projectile;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@PluginDescriptor(
        name = "Doom Assistant",
        description = "Doom of Mokhaiotl assistant with timing profiles, prayer reactions, and fail-safe exits",
        tags = {"doom", "mokhaiotl", "assistant", "pvm"}
)
public class DoomAssistantPlugin extends Plugin
{
    private static final String BOSS_NAME = "Doom of Mokhaiotl";

    @Inject
    private Client client;

    @Inject
    private DoomAssistantConfig config;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private DoomAssistantOverlay overlay;

    private NavigationButton navigationButton;
    private PluginPanel pluginPanel;

    private int currentTick = 0;
    private int encounterStartTick = -1;
    private int lastShockwaveAlertTick = -9999;
    private int lastRockAlertTick = -9999;
    private int lastFailSafeTick = -9999;
    private int lastProjectileTick = -9999;

    private DoomAttackStyle lastDetectedStyle = DoomAttackStyle.UNKNOWN;
    private String currentCallout = "Waiting for Doom...";
    private String currentPhase = "Idle";

    private Set<Integer> magicProjectileIds = new HashSet<>();
    private Set<Integer> rangedProjectileIds = new HashSet<>();
    private Set<Integer> meleeProjectileIds = new HashSet<>();
    private Set<Integer> rockProjectileIds = new HashSet<>();
    private Set<Integer> dashProjectileIds = new HashSet<>();
    private Set<Integer> slamProjectileIds = new HashSet<>();

    private final List<MechanicTileMarker> mechanicMarkers = new ArrayList<>();

    @Provides
    DoomAssistantConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(DoomAssistantConfig.class);
    }

    @Override
    protected void startUp()
    {
        reloadProjectileIds();

        pluginPanel = new DoomAssistantPanel(this);
        navigationButton = NavigationButton.builder()
                .tooltip("Doom Assistant")
                .icon(loadIcon())
                .priority(-1000)
                .panel(pluginPanel)
                .build();

        clientToolbar.addNavigation(navigationButton);
        overlayManager.add(overlay);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
            navigationButton = null;
        }

        mechanicMarkers.clear();
        currentCallout = "Disabled";
        currentPhase = "Idle";
        encounterStartTick = -1;
        lastDetectedStyle = DoomAttackStyle.UNKNOWN;
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        currentTick++;
        pruneExpiredMechanicMarkers();

        if (!config.enabled() || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            currentCallout = "Assistant disabled or not logged in.";
            return;
        }

        reloadProjectileIds();

        NpcEx doom = getDoom();
        if (doom == null || doom.isDead())
        {
            encounterStartTick = -1;
            currentPhase = "Idle";
            currentCallout = "Locate Doom and start encounter.";
            return;
        }

        if (encounterStartTick < 0)
        {
            encounterStartTick = currentTick;
            currentCallout = "Encounter started: maintain spacing and watch projectiles.";
        }

        if (isFailSafeTriggered())
        {
            doFailSafeExit();
            return;
        }

        applyProjectilePrayer();
        handleTimingAlerts();
        handleSpacingAlert(doom);
        inferPhase();
    }

    @Subscribe
    public void onProjectileMoved(ProjectileMoved event)
    {
        if (!config.enabled())
        {
            return;
        }

        Projectile projectile = event.getProjectile();
        if (projectile == null)
        {
            return;
        }

        int id = projectile.getId();
        LocalPoint impactLocal = event.getPosition();
        WorldPoint impactWorld = impactLocal != null ? WorldPoint.fromLocal(client, impactLocal) : null;

        registerMechanicFromProjectile(id, impactWorld);

        DoomAttackStyle style = classifyProjectile(id);
        if (style == DoomAttackStyle.UNKNOWN)
        {
            return;
        }

        lastDetectedStyle = style;
        lastProjectileTick = currentTick;
        currentCallout = "Incoming " + style.getDisplay() + " projectile.";
    }

    private void registerMechanicFromProjectile(int projectileId, WorldPoint impactWorld)
    {
        if (impactWorld == null)
        {
            return;
        }

        if (rockProjectileIds.contains(projectileId))
        {
            addMechanicMarker(MechanicType.FALLING_ROCK, impactWorld);
            currentCallout = "Falling rocks: move off marked tiles.";
            return;
        }

        if (dashProjectileIds.contains(projectileId))
        {
            addMechanicMarker(MechanicType.DASH_CAR, impactWorld);
            currentCallout = "Dash/Car lane detected: sidestep lane.";
            return;
        }

        if (slamProjectileIds.contains(projectileId))
        {
            addMechanicMarker(MechanicType.SLAM_SHOCKWAVE, impactWorld);
            currentCallout = "Slam marker detected: prepare shockwave movement.";
            return;
        }

        // Generic hazard fallback for unknown-but-user-tracked projectile sets.
        if (!magicProjectileIds.contains(projectileId)
                && !rangedProjectileIds.contains(projectileId)
                && !meleeProjectileIds.contains(projectileId))
        {
            addMechanicMarker(MechanicType.GENERIC_HAZARD, impactWorld);
        }
    }

    private void addMechanicMarker(MechanicType type, WorldPoint point)
    {
        int duration = Math.max(1, config.mechanicOverlayTicks());
        mechanicMarkers.add(new MechanicTileMarker(type, point, currentTick + duration));
    }

    private void pruneExpiredMechanicMarkers()
    {
        Iterator<MechanicTileMarker> it = mechanicMarkers.iterator();
        while (it.hasNext())
        {
            MechanicTileMarker marker = it.next();
            if (marker.expiresTick < currentTick)
            {
                it.remove();
            }
        }
    }

    private void applyProjectilePrayer()
    {
        if (!config.autoPray() || lastDetectedStyle == DoomAttackStyle.UNKNOWN)
        {
            return;
        }

        if (currentTick - lastProjectileTick > 3)
        {
            return;
        }

        PrayerAPI target = lastDetectedStyle.getOverhead();
        if (target == null || !target.hasLevelFor())
        {
            return;
        }

        PrayerAPI active = PrayerAPI.getActiveOverhead();
        if (active == target)
        {
            return;
        }

        if (active != null)
        {
            active.turnOff();
        }

        target.turnOn();
        currentCallout = "Prayer switched: " + target.name();
    }

    private void handleTimingAlerts()
    {
        DoomTimingProfile profile = DoomTimingProfile.forDelve(config.delveLevel());
        int elapsed = currentTick - encounterStartTick;

        int shockwaveCadence = profile.getShockwaveCadenceTicks();
        int shockwaveWindow = elapsed % shockwaveCadence;
        if (shockwaveWindow >= shockwaveCadence - profile.getHazardAlertLeadTicks() && currentTick - lastShockwaveAlertTick > shockwaveCadence / 2)
        {
            currentCallout = "Shockwave soon: prepare slam pattern movement.";
            alert("Shockwave incoming soon.");
            lastShockwaveAlertTick = currentTick;
        }

        int rockCadence = profile.getRockThrowCadenceTicks();
        int rockWindow = elapsed % rockCadence;
        if (rockWindow >= rockCadence - profile.getHazardAlertLeadTicks() && currentTick - lastRockAlertTick > rockCadence / 2)
        {
            currentCallout = "Rock throw window: set safe rockblock line.";
            alert("Rock throw window approaching.");
            lastRockAlertTick = currentTick;
        }
    }

    private void handleSpacingAlert(NpcEx doom)
    {
        WorldPoint local = client.getLocalPlayer().getWorldLocation();
        if (local == null || doom.getWorldPoint() == null)
        {
            return;
        }

        int distance = local.distanceTo(doom.getWorldPoint());
        if (distance <= 1)
        {
            currentCallout = "Too close: step away to avoid tongue melee.";
        }
    }

    private void inferPhase()
    {
        int elapsed = currentTick - encounterStartTick;
        if (config.delveLevel() <= 4)
        {
            currentPhase = "Base rotation";
            return;
        }

        if (elapsed < 35)
        {
            currentPhase = "Pre-shield";
        }
        else if (elapsed < 60)
        {
            currentPhase = "Shield/Car transition";
        }
        else
        {
            currentPhase = config.delveLevel() >= 7 ? "Deep cycle" : "Post-car loop";
        }
    }

    private boolean isFailSafeTriggered()
    {
        int maxHp = SkillAPI.getLevel(Skill.HITPOINTS);
        int curHp = SkillAPI.getBoostedLevel(Skill.HITPOINTS);
        int hpPercent = maxHp <= 0 ? 100 : (int) Math.round((curHp * 100.0) / maxHp);

        int prayer = SkillAPI.getBoostedLevel(Skill.PRAYER);
        return hpPercent <= clampPercent(config.escapeHpPercent()) || prayer <= Math.max(0, config.escapePrayerLevel());
    }

    private void doFailSafeExit()
    {
        if (currentTick - lastFailSafeTick < 4)
        {
            return;
        }

        lastFailSafeTick = currentTick;
        currentPhase = "Fail-safe";

        ItemEx teleport = InventoryAPI.getItem(config.teleportItemName());
        if (teleport != null)
        {
            InventoryAPI.interact(teleport, config.teleportAction(), "Break", "Teleport");
            currentCallout = "Fail-safe triggered: teleporting to safety.";
            alert("Fail-safe: emergency teleport used.");
            return;
        }

        TileObjectEx lobbyObject = TileObjectAPI.getContains(config.lobbyTargetName());
        if (lobbyObject != null)
        {
            TileObjectAPI.interact(lobbyObject, config.lobbyAction(), "Climb", "Use", "Pass");
            currentCallout = "Fail-safe triggered: routing to lobby object.";
            alert("Fail-safe: routing to lobby object.");
            return;
        }

        NpcEx npc = new NpcQuery().withNameContains(config.lobbyTargetName()).sortNearest().first();
        if (npc != null)
        {
            NpcAPI.interact(npc, config.lobbyAction(), "Talk-to", "Use", "Climb");
            currentCallout = "Fail-safe triggered: routing via lobby NPC.";
            alert("Fail-safe: routing via NPC.");
            return;
        }

        currentCallout = "Fail-safe active: no escape route found (check config).";
        alert("Fail-safe active but escape route is not configured correctly.");
    }

    private DoomAttackStyle classifyProjectile(int projectileId)
    {
        if (magicProjectileIds.contains(projectileId))
        {
            return DoomAttackStyle.MAGIC;
        }

        if (rangedProjectileIds.contains(projectileId))
        {
            return DoomAttackStyle.RANGED;
        }

        if (meleeProjectileIds.contains(projectileId))
        {
            return DoomAttackStyle.MELEE;
        }

        return DoomAttackStyle.UNKNOWN;
    }

    private NpcEx getDoom()
    {
        return new NpcQuery()
                .withNameContains(BOSS_NAME)
                .alive()
                .sortNearest()
                .first();
    }

    private int clampPercent(int value)
    {
        return Math.max(1, Math.min(99, value));
    }

    private void reloadProjectileIds()
    {
        magicProjectileIds = parseIdSet(config.magicProjectileIds());
        rangedProjectileIds = parseIdSet(config.rangedProjectileIds());
        meleeProjectileIds = parseIdSet(config.meleeProjectileIds());
        rockProjectileIds = parseIdSet(config.rockProjectileIds());
        dashProjectileIds = parseIdSet(config.dashProjectileIds());
        slamProjectileIds = parseIdSet(config.slamProjectileIds());
    }

    private Set<Integer> parseIdSet(String csv)
    {
        Set<Integer> ids = new HashSet<>();
        if (csv == null || csv.trim().isEmpty())
        {
            return ids;
        }

        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(token -> {
                    try
                    {
                        ids.add(Integer.parseInt(token));
                    }
                    catch (NumberFormatException ignored)
                    {
                    }
                });

        return ids;
    }

    private void alert(String message)
    {
        if (config.announceWarnings())
        {
            MessageUtil.sendChatMessage(new Color(255, 170, 80), "[Doom Assistant] " + message);
        }
    }

    private BufferedImage loadIcon()
    {
        try
        {
            BufferedImage icon = ImageUtil.loadImageResource(getClass(), "doom_icon.png");
            if (icon != null)
            {
                return icon;
            }
        }
        catch (Exception ignored)
        {
        }

        BufferedImage fallback = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = fallback.createGraphics();
        g.setColor(new Color(40, 40, 40));
        g.fillRect(0, 0, 16, 16);
        g.setColor(new Color(220, 90, 60));
        g.drawString("D", 4, 12);
        g.dispose();
        return fallback;
    }

    String getCurrentCallout()
    {
        return currentCallout;
    }

    String getCurrentPhase()
    {
        return currentPhase;
    }

    String getLastStyleText()
    {
        return lastDetectedStyle.getDisplay();
    }

    int getTicksSinceStart()
    {
        return encounterStartTick < 0 ? 0 : Math.max(0, currentTick - encounterStartTick);
    }

    int getCurrentHpPercent()
    {
        int maxHp = SkillAPI.getLevel(Skill.HITPOINTS);
        int curHp = SkillAPI.getBoostedLevel(Skill.HITPOINTS);
        return maxHp <= 0 ? 0 : (int) Math.round((curHp * 100.0) / maxHp);
    }

    int getCurrentPrayer()
    {
        return SkillAPI.getBoostedLevel(Skill.PRAYER);
    }

    boolean showMechanicOverlays()
    {
        return config.showMechanicOverlays();
    }

    List<MechanicTileMarker> getMechanicMarkers()
    {
        return mechanicMarkers;
    }

    static final class MechanicTileMarker
    {
        private final MechanicType type;
        private final WorldPoint worldPoint;
        private final int expiresTick;

        private MechanicTileMarker(MechanicType type, WorldPoint worldPoint, int expiresTick)
        {
            this.type = type;
            this.worldPoint = worldPoint;
            this.expiresTick = expiresTick;
        }

        MechanicType getType()
        {
            return type;
        }

        WorldPoint getWorldPoint()
        {
            return worldPoint;
        }
    }

    enum MechanicType
    {
        FALLING_ROCK("ROCK", new Color(255, 140, 0), new Color(255, 140, 0, 50)),
        DASH_CAR("DASH", new Color(255, 70, 70), new Color(255, 70, 70, 40)),
        SLAM_SHOCKWAVE("SLAM", new Color(255, 255, 0), new Color(255, 255, 0, 35)),
        GENERIC_HAZARD("HAZARD", new Color(180, 0, 255), new Color(180, 0, 255, 30));

        private final String label;
        private final Color outline;
        private final Color fill;

        MechanicType(String label, Color outline, Color fill)
        {
            this.label = label;
            this.outline = outline;
            this.fill = fill;
        }

        String getLabel()
        {
            return label;
        }

        Color getOutline()
        {
            return outline;
        }

        Color getFill()
        {
            return fill;
        }
    }
}
