package com.tonic.plugins.combatprayer;

import com.tonic.Static;
import com.tonic.api.widgets.PrayerAPI;
import com.tonic.data.wrappers.NpcEx;
import com.tonic.services.GameManager;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.ChatMessage;

import java.util.*;

/**
 * Manages combat prayers based on OSRS mechanics and NPC attack styles.
 * Automatically selects the appropriate protection prayers for different combat styles.
 */
public class PrayerManager
{
    // prayer tick rates
    private static final int TICKS_TO_SWITCH_PRAYER = 1;
    
    // prayer drain rates (points per tick)
    private static final double PROTECT_FROM_MAGIC_DRAIN = 20.0;
    private static final double PROTECT_FROM_MISSILES_DRAIN = 20.0;
    private static final double PROTECT_FROM_MELEE_DRAIN = 20.0;
    
    private final Client client;
    private final ConfigManager configManager;
    private PrayerPanel panel;
    private PrayerAPI activeProtectionPrayer = null;
    private NpcEx currentTarget = null;
    private int lastAttackTick = 0;
    private int lastDamageTick = 0;
    private final Map<String, String> npcPrayerReqs = new HashMap<>();

    public PrayerManager(Client client, ConfigManager configManager)
    {
        this.client = client;
        this.configManager = configManager;
        this.panel = new PrayerPanel(this);
        
        // Initialize NPC prayer requirements for common bosses
        initializeNpcPrayerRequirements();
    }

    public PrayerPanel getPanel()
    {
        return panel;
    }

    private void initializeNpcPrayerRequirements()
    {
        // Common NPC prayer requirements based on OSRS mechanics
        // Format: NPC name -> required prayer
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
        // Disable all prayers on shutdown
        PrayerAPI.disableAll();
    }

    public void update()
    {
        // Check if player is in combat
        if (!isInCombat())
        {
            handleIdleState();
            return;
        }

        // Get current target
        currentTarget = getCurrentTarget();
        
        // Manage overhead prayers (protection, smite, etc.)
        manageOverheadPrayers();
        
        // Update panel
        panel.update();
    }

    private boolean isInCombat()
    {
        // Check if player has been attacked or has attacked recently (within last 10 ticks)
        int tickCount = client.getTickCount();
        return tickCount - lastAttackTick <= 10 || tickCount - lastDamageTick <= 10;
    }

    public void onChatMessage(ChatMessage event)
    {
        String message = event.getMessage();
        
        // Track when player attacks
        if (message.contains("Hitmark") || message.contains("hits") || 
            message.contains("grazes") || message.contains("blocks") || 
            message.contains("misses") || message.contains("damage"))
        {
            lastAttackTick = client.getTickCount();
        }
        
        // Track when you take damage
        if (message.contains("You take") || message.contains("take damage") || 
            message.contains("Your") || message.contains("is hitting"))
        {
            lastDamageTick = client.getTickCount();
        }
    }

    private void handleIdleState()
    {
        currentTarget = null;
        
        // Keep overhead prayers active if they were already active
        PrayerAPI overhead = PrayerAPI.getActiveOverhead();
        if (overhead != null && isOverheadPrayerStayingActive(overhead))
        {
            activeProtectionPrayer = overhead;
        }
        else
        {
            // Disable all prayers when idle
            PrayerAPI.disableAll();
            activeProtectionPrayer = null;
        }
    }

    private void manageOverheadPrayers()
    {
        PrayerAPI activeOverhead = PrayerAPI.getActiveOverhead();
        
        if (currentTarget != null)
        {
            // Determine what type of attacks the NPC uses
            String npcName = currentTarget.getComposition().getName();
            String requiredPrayerName = npcPrayerReqs.get(npcName);
            
            if (requiredPrayerName != null)
            {
                // Boss specifically requires a prayer
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
                catch (Exception e)
                {
                    // Fallback to damage type detection
                }
            }
            
            // Auto-select overhead prayer based on detected attack style
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
                // No overhead prayer needed, but one is active
                activeOverhead.turnOff();
                activeProtectionPrayer = null;
            }
        }
        else if (activeOverhead != null && !isOverheadPrayerStayingActive(activeOverhead))
        {
            // Disable overhead prayer if we're not in combat
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
        
        // Check NPC's attack styles from composition
        String[] actions = composition.getActions();
        
        // Check for "Magic" in actions - indicates magic attacks
        for (String action : actions)
        {
            if (action != null && action.equalsIgnoreCase("Magic"))
            {
                return PrayerAPI.PROTECT_FROM_MAGIC;
            }
        }
        
        // Check for "Range" or "Ranged" in actions - indicates ranged attacks
        for (String action : actions)
        {
            if (action != null && (action.equalsIgnoreCase("Range") || 
                                  action.equalsIgnoreCase("Ranged")))
            {
                return PrayerAPI.PROTECT_FROM_MISSILES;
            }
        }
        
        // Default to melee protection
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
        // Certain overhead prayers should stay active when in combat
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

    // Getters for the panel
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
        return active != null && 
               active != PrayerAPI.PROTECT_FROM_MAGIC && 
               active != PrayerAPI.PROTECT_FROM_MISSILES;
    }
    
    public com.tonic.data.wrappers.NpcEx getCurrentTarget()
    {
        return currentTarget;
    }
    
    public PrayerAPI getActivePrayer()
    {
        return activeProtectionPrayer;
    }
}
