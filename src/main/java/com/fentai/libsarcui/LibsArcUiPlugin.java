package com.fentai.libsarcui;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class LibsArcUiPlugin extends JavaPlugin
{
    private static final List<String> EXAMPLE_RESOURCES = List.of(
            "ui/example/settings.yml",
            "ui/example/background.yml",
            "ui/example/title.yml",
            "ui/example/subtitle/settings.yml",
            "ui/example/subtitle/text.yml"
    );

    private UiManager uiManager;

    @Override
    public void onEnable()
    {
        saveDefaultConfig();
        createInitialUiTree();
        uiManager = new UiManager(this);
        uiManager.start();
        getLogger().info("libs-arc-ui 已启动：YAML 节点树正在即时监测，无需 reload。");
    }

    @Override
    public void onDisable()
    {
        if (uiManager != null)
        {
            uiManager.stop();
            uiManager = null;
        }
    }

    private void createInitialUiTree()
    {
        Path uiDirectory = getDataFolder().toPath().resolve("ui");
        try
        {
            Files.createDirectories(uiDirectory);
            if (!getConfig().getBoolean("create-example", true) || !isEmpty(uiDirectory))
            {
                return;
            }
            for (String resource : EXAMPLE_RESOURCES)
            {
                saveResource(resource, false);
            }
        }
        catch (IOException exception)
        {
            getLogger().severe("无法创建 ui 配置目录: " + exception.getMessage());
        }
    }

    private boolean isEmpty(Path directory) throws IOException
    {
        try (var stream = Files.list(directory))
        {
            return stream.findAny().isEmpty();
        }
    }
}
