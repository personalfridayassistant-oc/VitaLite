package com.tonic.plugins.weapongear;

import com.google.inject.Provides;
import com.tonic.Logger;
import com.tonic.api.threaded.Delays;
import com.tonic.api.widgets.EquipmentAPI;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.api.widgets.PrayerAPI;
import com.tonic.data.EquipmentSlot;
import com.tonic.data.wrappers.ItemEx;
import com.tonic.util.VitaPlugin;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@PluginDescriptor(
        name = "# Weapon Gear Auto-Equip",
        description = "Auto-equips configured gear + prayers when a configured weapon is equipped",
        tags = {"gear", "switch", "prayer", "equipment"}
)
public class WeaponGearAutoEquipPlugin extends VitaPlugin
{
    @Inject
    private WeaponGearAutoEquipConfig config;

    @Inject
    private Client client;

    private int lastWeaponId = -1;
    private int lastProcessedWeaponId = -1;

    @Provides
    WeaponGearAutoEquipConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(WeaponGearAutoEquipConfig.class);
    }

    @Override
    public void loop() throws Exception
    {
        if (!config.enabled())
        {
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            return;
        }

        ItemEx weapon = EquipmentAPI.fromSlot(EquipmentSlot.WEAPON);
        int currentWeaponId = weapon == null ? -1 : weapon.getId();

        if (currentWeaponId != lastWeaponId)
        {
            lastWeaponId = currentWeaponId;
            if (currentWeaponId == -1)
            {
                lastProcessedWeaponId = -1;
                return;
            }
        }

        if (currentWeaponId == -1 || currentWeaponId == lastProcessedWeaponId)
        {
            return;
        }

        SwitchSelection selection = findSelectionForWeapon(weapon);
        if (selection == null)
        {
            lastProcessedWeaponId = currentWeaponId;
            return;
        }

        boolean equippedAny = equipConfiguredItems(selection);
        if (equippedAny)
        {
            Delays.tick(Math.max(1, config.switchDelayTicks()));
        }

        enableConfiguredPrayers(selection);
        lastProcessedWeaponId = currentWeaponId;
        Delays.tick(Math.max(1, config.switchDelayTicks()));
    }

    private SwitchSelection findSelectionForWeapon(ItemEx weapon)
    {
        if (weapon == null)
        {
            return null;
        }

        for (SwitchSelection selection : getSelections())
        {
            if (selection.matchesWeapon(weapon))
            {
                return selection;
            }
        }
        return null;
    }

    private boolean equipConfiguredItems(SwitchSelection selection)
    {
        boolean equippedAny = false;

        for (String token : selection.equipTokens)
        {
            ItemEx inventoryItem = findInventoryItem(token);
            if (inventoryItem == null)
            {
                continue;
            }

            InventoryAPI.interact(inventoryItem, "Wield", "Wear", "Equip");
            equippedAny = true;
        }

        return equippedAny;
    }

    private void enableConfiguredPrayers(SwitchSelection selection)
    {
        for (String prayerToken : selection.prayerTokens)
        {
            PrayerAPI prayer = parsePrayer(prayerToken);
            if (prayer == null)
            {
                Logger.warn("[WeaponGearAutoEquip] Unknown prayer token: " + prayerToken);
                continue;
            }

            if (prayer.hasLevelFor() && !prayer.isActive())
            {
                prayer.turnOn();
            }
        }
    }

    private ItemEx findInventoryItem(String token)
    {
        if (token == null || token.trim().isEmpty())
        {
            return null;
        }

        String trimmed = token.trim();
        if (isInteger(trimmed))
        {
            return InventoryAPI.getItem(Integer.parseInt(trimmed));
        }

        return InventoryAPI.getItem(trimmed);
    }

    private PrayerAPI parsePrayer(String token)
    {
        if (token == null || token.trim().isEmpty())
        {
            return null;
        }

        String normalized = token.trim().toUpperCase(Locale.ENGLISH)
                .replace(' ', '_')
                .replace('-', '_');

        try
        {
            return PrayerAPI.valueOf(normalized);
        }
        catch (IllegalArgumentException ex)
        {
            return null;
        }
    }

    private List<SwitchSelection> getSelections()
    {
        List<SwitchSelection> selections = new ArrayList<>(10);
        selections.add(new SwitchSelection(config.triggerWeapon1(), config.equipItems1(), config.prayers1()));
        selections.add(new SwitchSelection(config.triggerWeapon2(), config.equipItems2(), config.prayers2()));
        selections.add(new SwitchSelection(config.triggerWeapon3(), config.equipItems3(), config.prayers3()));
        selections.add(new SwitchSelection(config.triggerWeapon4(), config.equipItems4(), config.prayers4()));
        selections.add(new SwitchSelection(config.triggerWeapon5(), config.equipItems5(), config.prayers5()));
        selections.add(new SwitchSelection(config.triggerWeapon6(), config.equipItems6(), config.prayers6()));
        selections.add(new SwitchSelection(config.triggerWeapon7(), config.equipItems7(), config.prayers7()));
        selections.add(new SwitchSelection(config.triggerWeapon8(), config.equipItems8(), config.prayers8()));
        selections.add(new SwitchSelection(config.triggerWeapon9(), config.equipItems9(), config.prayers9()));
        selections.add(new SwitchSelection(config.triggerWeapon10(), config.equipItems10(), config.prayers10()));
        return selections;
    }

    private static List<String> splitCsv(String csv)
    {
        if (csv == null || csv.trim().isEmpty())
        {
            return Collections.emptyList();
        }

        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static boolean isInteger(String value)
    {
        try
        {
            Integer.parseInt(value);
            return true;
        }
        catch (NumberFormatException ex)
        {
            return false;
        }
    }

    private static class SwitchSelection
    {
        private final String triggerToken;
        private final List<String> equipTokens;
        private final List<String> prayerTokens;

        private SwitchSelection(String triggerToken, String equipItemsCsv, String prayersCsv)
        {
            this.triggerToken = triggerToken == null ? "" : triggerToken.trim();
            this.equipTokens = splitCsv(equipItemsCsv);
            this.prayerTokens = splitCsv(prayersCsv);
        }

        private boolean matchesWeapon(ItemEx weapon)
        {
            if (weapon == null || triggerToken.isEmpty())
            {
                return false;
            }

            if (isInteger(triggerToken))
            {
                return weapon.getId() == Integer.parseInt(triggerToken);
            }

            String name = Optional.ofNullable(weapon.getName()).orElse("").toLowerCase(Locale.ENGLISH);
            return name.contains(triggerToken.toLowerCase(Locale.ENGLISH));
        }
    }
}
