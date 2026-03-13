package net.runelite.client.plugins.decrypt;

import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import java.nio.file.Path;

@Slf4j
@PluginDescriptor(
        name = "decrypt",
        description = "Analyzes packet captures/JAR payloads and reconstructs Java source stubs",
        tags = {"pcap", "jar", "reverse", "analysis", "decrypt"}
)
public class DecryptPlugin extends Plugin
{
    @Inject
    private DecryptConfig config;

    @Inject
    private DecryptService decryptService;

    @Provides
    DecryptConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(DecryptConfig.class);
    }

    @Override
    protected void startUp()
    {
        if (!config.enabledOnStartup())
        {
            log.info("decrypt plugin loaded; analysis disabled on startup");
            return;
        }

        String input = config.inputPath().trim();
        if (input.isEmpty())
        {
            log.warn("decrypt plugin enabled but no inputPath configured");
            return;
        }

        decryptService.analyze(Path.of(input), Path.of(config.outputDirectory()), config.overwrite());
    }
}
