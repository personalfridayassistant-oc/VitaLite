package net.runelite.client.plugins.decrypt;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("decrypt")
public interface DecryptConfig extends Config
{
    @ConfigItem(
            keyName = "enabledOnStartup",
            name = "Analyze on startup",
            description = "Runs analysis when plugin starts"
    )
    default boolean enabledOnStartup()
    {
        return true;
    }

    @ConfigItem(
            keyName = "inputPath",
            name = "Input file",
            description = "Path to a .jar, .pcap, or .pcapng capture file"
    )
    default String inputPath()
    {
        return "";
    }

    @ConfigItem(
            keyName = "outputDirectory",
            name = "Output directory",
            description = "Directory where extracted/decompiled files will be written"
    )
    default String outputDirectory()
    {
        return System.getProperty("user.home") + "/.runelite/decrypt-output";
    }

    @ConfigItem(
            keyName = "overwrite",
            name = "Overwrite existing files",
            description = "Replace files if they already exist"
    )
    default boolean overwrite()
    {
        return false;
    }
}
