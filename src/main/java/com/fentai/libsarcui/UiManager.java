package com.fentai.libsarcui;

import com.fentai.libsarcui.config.UiConfigCompleter;
import com.fentai.libsarcui.config.UiTreeLoader;
import com.fentai.libsarcui.model.UiLoadResult;
import com.fentai.libsarcui.render.StaticUiRenderer;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.PluginDisableEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UiManager implements Listener
{
    private final LibsArcUiPlugin plugin;
    private final Path uiDirectory;
    private final UiConfigCompleter completer;
    private final UiTreeLoader loader;
    private final StaticUiRenderer renderer;
    private Map<String, FileStamp> lastSnapshot = Map.of();
    private String lastWarningSignature = "";
    private ScheduledTask monitorTask;
    private int ticksUntilScan;
    private final AtomicBoolean forceRefresh = new AtomicBoolean(true);
    private boolean craftEngineReloadHook;

    public UiManager(LibsArcUiPlugin plugin)
    {
        this.plugin = plugin;
        this.uiDirectory = plugin.getDataFolder().toPath().resolve("ui");
        this.completer = new UiConfigCompleter(uiDirectory,
                () -> plugin.getConfig().getString("render.default-world", "world"));
        this.loader = new UiTreeLoader(plugin, uiDirectory);
        this.renderer = new StaticUiRenderer(plugin);
    }

    public void start()
    {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        hookCraftEngineReload();
        monitorTask = plugin.getServer().getGlobalRegionScheduler()
                .runAtFixedRate(plugin, task -> tick(), 1L, 1L);
    }

    private void tick()
    {
        if (--ticksUntilScan > 0)
        {
            return;
        }
        ticksUntilScan = Math.max(1, Math.min(1200,
                plugin.getConfig().getInt("monitor.interval-ticks", 2)));
        scan();
    }

    private void scan()
    {
        UiConfigCompleter.CompletionResult completion = completer.completeTree();
        Map<String, FileStamp> snapshot = snapshot();
        boolean changed = forceRefresh.getAndSet(false) || completion.changed() || !Objects.equals(snapshot, lastSnapshot);
        lastSnapshot = snapshot;
        if (!changed)
        {
            logWarnings(completion.warnings());
            return;
        }

        plugin.reloadConfig();
        UiLoadResult result = loader.load();
        List<String> warnings = new ArrayList<>(completion.warnings());
        warnings.addAll(result.warnings());
        if (result.successful())
        {
            warnings.addAll(renderer.sync(result.elements()));
        }
        logWarnings(warnings);
    }

    private Map<String, FileStamp> snapshot()
    {
        Map<String, FileStamp> result = new LinkedHashMap<>();
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        addStamp(result, config, "config.yml");
        if (!Files.exists(uiDirectory))
        {
            return result;
        }
        try (var stream = Files.walk(uiDirectory))
        {
            stream.filter(path -> !path.equals(uiDirectory))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> addStamp(result, path,
                            "ui/" + uiDirectory.relativize(path).toString().replace('\\', '/')));
        }
        catch (IOException exception)
        {
            result.put("<scan-error>", new FileStamp(-1, -1, false));
        }
        return Map.copyOf(result);
    }

    private void addStamp(Map<String, FileStamp> target, Path path, String key)
    {
        try
        {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            target.put(key, new FileStamp(attributes.lastModifiedTime().toMillis(),
                    attributes.isRegularFile() ? attributes.size() : 0L,
                    attributes.isDirectory()));
        }
        catch (IOException exception)
        {
            target.put(key, new FileStamp(-1L, -1L, false));
        }
    }

    private void logWarnings(List<String> warnings)
    {
        String signature = String.join("\n", warnings);
        if (signature.equals(lastWarningSignature))
        {
            return;
        }
        lastWarningSignature = signature;
        for (String warning : warnings)
        {
            plugin.getLogger().warning(warning);
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event)
    {
        forceRefresh.set(true);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event)
    {
        renderer.onEntitiesLoad(event.getChunk(), event.getEntities());
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event)
    {
        renderer.onEntitiesUnload(event.getEntities());
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event)
    {
        if (event.getPlugin().getName().equals("CraftEngine"))
        {
            hookCraftEngineReload();
            forceRefresh.set(true);
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event)
    {
        if (event.getPlugin().getName().equals("CraftEngine")) forceRefresh.set(true);
    }

    private void hookCraftEngineReload()
    {
        if (craftEngineReloadHook) return;
        var host = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if (host == null) return;
        try
        {
            Class<? extends Event> eventClass = Class.forName(
                    "net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent", true,
                    host.getClass().getClassLoader()).asSubclass(Event.class);
            plugin.getServer().getPluginManager().registerEvent(eventClass, this, EventPriority.MONITOR,
                    (listener, event) -> forceRefresh.set(true), plugin);
            craftEngineReloadHook = true;
        }
        catch (ClassNotFoundException | LinkageError exception)
        {
            plugin.getLogger().warning("当前 CraftEngine 不提供重载事件；修改 UI YAML 后将重新解析图片。");
        }
    }

    public void stop()
    {
        if (monitorTask != null)
        {
            monitorTask.cancel();
            monitorTask = null;
        }
        renderer.destroy();
    }

    private record FileStamp(long modifiedMillis, long size, boolean directory)
    {
    }
}
