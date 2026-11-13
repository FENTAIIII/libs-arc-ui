package com.fentai.libsarcui.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 这里是节点树的一个逻辑。
 */
public final class UiConfigCompleter
{
    public static final String SETTINGS_FILE = "settings.yml";
    public static final String SETTINGS_FILE_ALIAS = "setting.yml";

    private final Path uiDirectory;
    private final Supplier<String> defaultWorld;

    public UiConfigCompleter(Path uiDirectory, Supplier<String> defaultWorld)
    {
        this.uiDirectory = uiDirectory;
        this.defaultWorld = defaultWorld;
    }

    public CompletionResult completeTree()
    {
        List<String> warnings = new ArrayList<>();
        boolean changed = false;
        try
        {
            Files.createDirectories(uiDirectory);
            List<Path> directories;
            try (var stream = Files.walk(uiDirectory))
            {
                directories = stream.filter(Files::isDirectory)
                        .filter(path -> !path.equals(uiDirectory))
                        .sorted(Comparator.comparingInt(Path::getNameCount))
                        .toList();
            }

            for (Path directory : directories)
            {
                boolean topLevel = uiDirectory.relativize(directory).getNameCount() == 1;
                changed |= completeSettings(directory, topLevel, warnings);
            }

            List<Path> elements;
            try (var stream = Files.walk(uiDirectory))
            {
                elements = stream.filter(Files::isRegularFile)
                        .filter(this::isYaml)
                        .filter(path -> !isSettings(path))
                        .sorted()
                        .toList();
            }
            for (Path element : elements)
            {
                changed |= completeElement(element, warnings);
            }
        }
        catch (IOException exception)
        {
            warnings.add("无法扫描UI目录: " + exception.getMessage());
        }
        return new CompletionResult(changed, warnings);
    }

    private boolean completeSettings(Path directory, boolean topLevel, List<String> warnings)
    {
        Path canonical = directory.resolve(SETTINGS_FILE);
        Path alias = directory.resolve(SETTINGS_FILE_ALIAS);
        Path target = Files.exists(canonical) ? canonical : (Files.exists(alias) ? alias : canonical);
        YamlConfiguration yaml = load(target, warnings);

        if (yaml == null)
        {
            return false;
        }

        boolean changed = false;
        changed |= setMissing(yaml, "enabled", true);

        if (topLevel)
        {
            String world = defaultWorld.get();
            changed |= setMissing(yaml, "center.world",
                    world == null || world.isBlank() ? "world" : world);
            changed |= setMissing(yaml, "center.x", 0.0);
            changed |= setMissing(yaml, "center.y", 100.0);
            changed |= setMissing(yaml, "center.z", 0.0);
        }
        else
        {
            changed |= setMissing(yaml, "offset.x", 0.0);
            changed |= setMissing(yaml, "offset.y", 0.0);
            changed |= setMissing(yaml, "offset.z", 0.0);
            changed |= setMissing(yaml, "offset.rotation.x", 0.0);
            changed |= setMissing(yaml, "offset.rotation.y", 0.0);
            changed |= setMissing(yaml, "offset.rotation.z", 0.0);
        }
        changed |= addTransformDefaults(yaml);

        if (!Files.exists(target))
        {
            yaml.options().setHeader(List.of(topLevel
                    ? "顶层节点：center 是世界绝对中心，是一个坐标；rotation/scale 则作用于整个子树。"
                    : "子节点：offset 相对父节点；offset.rotation 绕父中心旋转偏移，不改变朝向；rotation/scale 则作用于整个子树。"));
            changed = true;
        }
        return changed && save(yaml, target, warnings);
    }

    private boolean completeElement(Path file, List<String> warnings)
    {
        YamlConfiguration yaml = load(file, warnings);

        if (yaml == null)
        {
            return false;
        }

        String inferredType = inferType(yaml);
        boolean changed = false;

        changed |= setMissing(yaml, "type", inferredType);
        changed |= setMissing(yaml, "visible", true);
        changed |= setMissing(yaml, "offset.x", 0.0);
        changed |= setMissing(yaml, "offset.y", 0.0);
        changed |= setMissing(yaml, "offset.z", 0.0);
        changed |= addTransformDefaults(yaml);
        changed |= setMissing(yaml, "z-index", 0);

        String type = yaml.getString("type", inferredType).trim().toLowerCase(Locale.ROOT);
        if (type.equals("block"))
        {
            changed |= setMissing(yaml, "block.data", "minecraft:stone");
        }
        else if (isRectangle(type))
        {
            changed |= setMissing(yaml, "rectangle.argb", List.of(255, 255, 255, 255));
            changed |= setMissing(yaml, "rectangle.width", 1.0);
            changed |= setMissing(yaml, "rectangle.height", 1.0);
        }
        else
        {
            changed |= setMissing(yaml, "text.content", "");
            changed |= setMissing(yaml, "text.font", "minecraft:default");
            changed |= setMissing(yaml, "text.character-size", 1.0);
            changed |= setMissing(yaml, "text.character-size-offset", 0.0);
            changed |= setMissing(yaml, "text.opacity", 255);
            changed |= setMissing(yaml, "text.alignment", "center");
            changed |= setMissing(yaml, "text.line-width", 200);
            changed |= setMissing(yaml, "text.shadowed", false);
            changed |= setMissing(yaml, "text.see-through", false);
            changed |= setMissing(yaml, "text.background-argb", List.of(0, 0, 0, 0));
        }
        return changed && save(yaml, file, warnings);
    }

    private boolean addTransformDefaults(YamlConfiguration yaml)
    {
        boolean changed = false;
        changed |= setMissing(yaml, "rotation.x", 0.0);
        changed |= setMissing(yaml, "rotation.y", 0.0);
        changed |= setMissing(yaml, "rotation.z", 0.0);
        changed |= setMissing(yaml, "scale.x", 1.0);
        changed |= setMissing(yaml, "scale.y", 1.0);
        changed |= setMissing(yaml, "scale.z", 1.0);
        return changed;
    }

    private String inferType(YamlConfiguration yaml)
    {
        if (yaml.isConfigurationSection("block")) return "block";
        if (yaml.isConfigurationSection("rectangle")
                || yaml.contains("argb")
                || yaml.contains("width")
                || yaml.contains("height"))
        {
            return "rectangle";
        }
        return "text";
    }

    private boolean isRectangle(String type)
    {
        return type.equals("rectangle") || type.equals("rect") || type.equals("color")
                || type.equals("color-block") || type.equals("block-color")
                || type.equals("色块") || type.equals("矩形");
    }

    private boolean setMissing(YamlConfiguration yaml, String path, Object value)
    {
        if (yaml.contains(path))
        {
            return false;
        }
        yaml.set(path, value);
        return true;
    }

    private YamlConfiguration load(Path path, List<String> warnings)
    {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        if (!Files.exists(path))
        {
            return yaml;
        }
        try
        {
            yaml.load(path.toFile());
            return yaml;
        }
        catch (IOException | InvalidConfigurationException exception)
        {
            warnings.add(relative(path) + " 无法读取，已保留原文件: " + exception.getMessage());
            return null;
        }
    }

    private boolean save(YamlConfiguration yaml, Path path, List<String> warnings)
    {
        try
        {
            Files.createDirectories(path.getParent());
            yaml.save(path.toFile());
            return true;
        }
        catch (IOException exception)
        {
            warnings.add(relative(path) + " 无法补全: " + exception.getMessage());
            return false;
        }
    }

    private boolean isYaml(Path path)
    {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    public static boolean isSettings(Path path)
    {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals(SETTINGS_FILE) || name.equals(SETTINGS_FILE_ALIAS);
    }

    private String relative(Path path)
    {
        try
        {
            return uiDirectory.relativize(path).toString();
        }
        catch (IllegalArgumentException ignored)
        {
            return path.toString();
        }
    }

    public record CompletionResult(boolean changed, List<String> warnings)
    {
    }
}
