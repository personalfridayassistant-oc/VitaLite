package com.tonic.plugins.combatprayer;

import com.tonic.api.widgets.PrayerAPI;
import com.tonic.data.wrappers.NpcEx;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.events.ChatMessage;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages combat prayers based on OSRS mechanics and NPC attack styles.
 * Automatically selects the appropriate protection prayers for different combat styles.
 */
public class PrayerManager
{
    private final Client client;
    private final PrayerPanel panel;
    private PrayerAPI activeProtectionPrayer;
    private NpcEx currentTarget;
    private int lastAttackTick;
    private int lastDamageTick;
    private final Map<String, String> npcPrayerReqs = new HashMap<>();

    public PrayerManager(Client client)
    {
        this.client = client;
        this.panel = new PrayerPanel(this);

        initializeNpcPrayerRequirements();
    }

    public PrayerPanel getPanel()
    {
        return panel;
    }

    private void initializeNpcPrayerRequirements()
    {
        npcPrayerReqs.put("General Graardor", "PIETY");
        npcPrayerReqs.put("K'ril Tsutsaroth", "PIETY");
        npcPrayerReqs.put("Zamorakian Champion", "PIETY");
        npcPrayerReqs.put("Kree'arra", "RIGOUR");
        npcPrayerReqs.put("Dareeyvik", "RIGOUR");
        npcPrayerReqs.put("Arceus", "AUGURY");
        npcPrayerReqs.put("K'kree'kra", "AUGURY");
        npcPrayerReqs.put("Zaros Champion", "CHIVALRY");
        npcPrayerReqs.put("Tormented Soul", "SMITE");
        npcPrayerReqs.put("Verak Lith", "STEEL_SKIN");
        npcPrayerReqs.put("Torag Corrupted", "PIETY");
        npcPrayerReqs.put("Verac Flayed", "CHIVALRY");
        npcPrayerReqs.put("Goliath", "PIETY");
        npcPrayerReqs.put("Dagganoth Prime", "PIETY");
        npcPrayerReqs.put("Dagganoth Rex", "PIETY");
        npcPrayerReqs.put("Dagganoth Rusty", "PIETY");
        npcPrayerReqs.put("TzTok Jad", "REDEMPTION");
        npcPrayerReqs.put("Venenatis", "PIETY");
        npcPrayerReqs.put("Spindel", "PIETY");
        npcPrayerReqs.put("Vasa Nihy", "RIGOUR");
        npcPrayerReqs.put("Vitrial", "RIGOUR");
        npcPrayerReqs.put("TzKal Zen", "AUGURY");
        npcPrayerReqs.put("YtMejKot", "AUGURY");
        npcPrayerReqs.put("Zakl'Ginst", "AUGURY");
        npcPrayerReqs.put("Kree'Arra", "RIGOUR");
        npcPrayerReqs.put("K'Kree'Kra", "AUGURY");
        npcPrayerReqs.put("TzKal Zuk", "AUGURY");
        npcPrayerReqs.put("Jalak", "PIETY");
        npcPrayerReqs.put("Giant Mole", "PIETY");
        npcPrayerReqs.put("Vorkath", "PIETY");
        npcPrayerReqs.put("Wintertodt", "CHIVALRY");
        npcPrayerReqs.put("Barrows", "PIETY");
        npcPrayerReqs.put("Cerberus", "PIETY");
        npcPrayerReqs.put("Chaos Elemental", "PIETY");
        npcPrayerReqs.put("Chaos Fanatic", "PIETY");
        npcPrayerReqs.put("Commander Zilyana", "PIETY");
        npcPrayerReqs.put("Grotesque Guardians", "PIETY");
        npcPrayerReqs.put("Hespori", "PIETY");
        npcPrayerReqs.put("Pharaoh's Sceptre", "PIETY");
        npcPrayerReqs.put("Sarachnis", "PIETY");
        npcPrayerReqs.put("Scorpia", "PIETY");
        npcPrayerReqs.put("Vet'ion", "PIETY");
        npcPrayerReqs.put("Volognath", "PIETY");
    }

    public void shutdown()
    {
        PrayerAPI.disableAll();
    }

    public void update()
    {
        if (!isInCombat())
        {
            handleIdleState();
            panel.update();
            return;
        }

        currentTarget = findCurrentTarget();
        manageOverheadPrayers();
        panel.update();
    }

    public boolean isInCombat()
    {
        int tickCount = client.getTickCount();
        return tickCount - lastAttackTick <= 10 || tickCount - lastDamageTick <= 10;
    }

    public void onChatMessage(ChatMessage event)
    {
        String message = event.getMessage();

        if (message.contains("Hitmark") || message.contains("hits")
                || message.contains("grazes") || message.contains("blocks")
                || message.contains("misses") || message.contains("damage"))
        {
            lastAttackTick = client.getTickCount();
        }

        if (message.contains("You take") || message.contains("take damage")
                || message.contains("Your") || message.contains("is hitting"))
        {
            lastDamageTick = client.getTickCount();
        }
    }

    private NpcEx findCurrentTarget()
    {
        if (client.getLocalPlayer() == null)
        {
            return null;
        }

        Actor interacting = client.getLocalPlayer().getInteracting();
        if (interacting instanceof NPC)
        {
            return new NpcEx((NPC) interacting);
        }

        return null;
    }

    private void handleIdleState()
    {
        currentTarget = null;

        PrayerAPI overhead = PrayerAPI.getActiveOverhead();
        if (overhead != null && isOverheadPrayerStayingActive(overhead))
        {
            activeProtectionPrayer = overhead;
            return;
        }

        PrayerAPI.disableAll();
        activeProtectionPrayer = null;
    }

    private void manageOverheadPrayers()
    {
        PrayerAPI activeOverhead = PrayerAPI.getActiveOverhead();

        if (currentTarget != null)
        {
            NPCComposition composition = currentTarget.getComposition();
            String npcName = composition != null ? composition.getName() : null;
            String requiredPrayerName = npcName != null ? npcPrayerReqs.get(npcName) : null;

            if (requiredPrayerName != null)
            {
                try
                {
                    PrayerAPI targetPrayer = PrayerAPI.valueOf(requiredPrayerName);
                    if (targetPrayer.hasLevelFor() && (!targetPrayer.isActive() || activeOverhead != targetPrayer))
                    {
                        setActivePrayer(targetPrayer);
                    }
                    activeProtectionPrayer = targetPrayer;
                    return;
                }
                catch (IllegalArgumentException ignored)
                {
                    // fall back to damage type detection
                }
            }

            PrayerAPI overhead = detectDamageType();
            if (overhead != null && overhead.hasLevelFor())
            {
                if (!overhead.isActive() || activeOverhead != overhead)
                {
                    setActivePrayer(overhead);
                }
                activeProtectionPrayer = overhead;
            }
            else if (overhead == null && activeOverhead != null)
            {
                activeOverhead.turnOff();
                activeProtectionPrayer = null;
            }
            return;
        }

        if (activeOverhead != null && !isOverheadPrayerStayingActive(activeOverhead))
        {
            activeOverhead.turnOff();
            activeProtectionPrayer = null;
        }
    }

    private PrayerAPI detectDamageType()
    {
        if (currentTarget == null)
        {
            return null;
        }

        NPCComposition composition = currentTarget.getComposition();
        if (composition == null)
        {
            return null;
        }

        String[] actions = composition.getActions();
        if (actions == null)
        {
            return PrayerAPI.PROTECT_FROM_MELEE;
        }

        for (String action : actions)
        {
            if (action != null && action.equalsIgnoreCase("Magic"))
            {
                return PrayerAPI.PROTECT_FROM_MAGIC;
            }
        }

        for (String action : actions)
        {
            if (action != null && (action.equalsIgnoreCase("Range") || action.equalsIgnoreCase("Ranged")))
            {
                return PrayerAPI.PROTECT_FROM_MISSILES;
            }
        }

        return PrayerAPI.PROTECT_FROM_MELEE;
    }

    private void setActivePrayer(PrayerAPI prayer)
    {
        if (prayer == null || !prayer.hasLevelFor())
        {
            return;
        }

        PrayerAPI activeOverhead = PrayerAPI.getActiveOverhead();
        if (activeOverhead != null && activeOverhead != prayer)
        {
            activeOverhead.turnOff();
        }

        prayer.turnOn();
        activeProtectionPrayer = prayer;
    }

    private boolean isOverheadPrayerStayingActive(PrayerAPI prayer)
    {
        if (prayer == null)
        {
            return false;
        }

        switch (prayer)
        {
            case PROTECT_FROM_MAGIC:
            case PROTECT_FROM_MISSILES:
            case PROTECT_FROM_MELEE:
            case RETRIBUTION:
            case REDEMPTION:
            case SMITE:
                return isInCombat();
            default:
                return false;
        }
    }

    public boolean isUsingRanged()
    {
        PrayerAPI active = PrayerAPI.getActiveOverhead();
        return active == PrayerAPI.PROTECT_FROM_MISSILES;
    }

    public boolean isUsingMagic()
    {
        PrayerAPI active = PrayerAPI.getActiveOverhead();
        return active == PrayerAPI.PROTECT_FROM_MAGIC;
    }

    public boolean isUsingMelee()
    {
        PrayerAPI active = PrayerAPI.getActiveOverhead();
        return active == PrayerAPI.PROTECT_FROM_MELEE;
    }

    public NpcEx getCurrentTarget()
    {
        return currentTarget;
    }

    public PrayerAPI getActivePrayer()
    {
        return activeProtectionPrayer;
    }
}
