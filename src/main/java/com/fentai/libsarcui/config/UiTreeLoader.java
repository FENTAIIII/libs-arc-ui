package com.fentai.libsarcui.config;

import com.fentai.libsarcui.LibsArcUiPlugin;
import com.fentai.libsarcui.model.ElementSpec;
import com.fentai.libsarcui.model.ElementType;
import com.fentai.libsarcui.model.LayerTransform;
import com.fentai.libsarcui.model.RotationPivot;
import com.fentai.libsarcui.model.UiLoadResult;
import com.fentai.libsarcui.model.Vec3;
import com.fentai.libsarcui.text.CraftEngineImageResolver;
import com.fentai.libsarcui.text.UiTextParser;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.TextDisplay;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.function.Function;

public final class UiTreeLoader
{
    private static final Matrix4f UNIT_RECTANGLE = new Matrix4f()
            .translate(0.4f, 0.0f, 0.0f)
            .scale(8.08f, 3.66f, 1.0f);

    private final Supplier<? extends FileConfiguration> configuration;
    private final Path uiDirectory;
    private final UiTextParser textParser;
    private final Function<String, BlockData> blockParser;

    public UiTreeLoader(LibsArcUiPlugin plugin, Path uiDirectory)
    {
        this(uiDirectory, plugin::getConfig, new UiTextParser(new CraftEngineImageResolver(
                () -> plugin.getServer().getPluginManager().getPlugin("CraftEngine"))));
    }

    UiTreeLoader(Path uiDirectory, Supplier<? extends FileConfiguration> configuration, UiTextParser textParser)
    {
        this(uiDirectory, configuration, textParser, Bukkit::createBlockData);
    }

    UiTreeLoader(Path uiDirectory, Supplier<? extends FileConfiguration> configuration, UiTextParser textParser,
                 Function<String, BlockData> blockParser)
    {
        this.configuration = configuration;
        this.uiDirectory = uiDirectory;
        this.textParser = textParser;
        this.blockParser = blockParser;
    }

    public UiLoadResult load()
    {
        List<ElementSpec> elements = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean successful = true;
        RotationPivot pivot;
        try
        {
            pivot = RotationPivot.parse(configuration.get().getString("editing.rotation-pivot", "self"));
        }
        catch (IllegalArgumentException exception)
        {
            return new UiLoadResult(false, List.of(), List.of(exception.getMessage()));
        }

        try
        {
            if (!Files.isDirectory(uiDirectory))
            {
                return new UiLoadResult(true, List.of(), List.of());
            }
            List<Path> roots;
            try (var stream = Files.list(uiDirectory))
            {
                roots = stream.filter(Files::isDirectory).sorted().toList();
            }
            for (Path root : roots)
            {
                try
                {
                    loadRoot(root, elements, warnings, pivot);
                }
                catch (ConfigReadException exception)
                {
                    successful = false;
                    warnings.add(exception.getMessage());
                }
            }
        }
        catch (IOException exception)
        {
            successful = false;
            warnings.add("无法读取 ui 目录: " + exception.getMessage());
        }
        return new UiLoadResult(successful, List.copyOf(elements), List.copyOf(warnings));
    }

    private void loadRoot(Path root, List<ElementSpec> output, List<String> warnings, RotationPivot pivot)
            throws IOException, ConfigReadException
    {
        YamlConfiguration settings = loadSettings(root);
        if (!settings.getBoolean("enabled", true))
        {
            return;
        }

        String world = settings.getString("center.world",
                configuration.get().getString("render.default-world", "world"));
        if (world == null || world.isBlank())
        {
            world = "world";
        }
        double anchorX = finite(settings.getDouble("center.x"), 0.0);
        double anchorY = finite(settings.getDouble("center.y", 100.0), 100.0);
        double anchorZ = finite(settings.getDouble("center.z"), 0.0);
        // A root has no parent: its explicit center remains the rotation pivot.
        Matrix4f rootMatrix = readLayerTransform(settings).applyTo(new Matrix4f(), RotationPivot.SELF);
        loadLayer(root, world, anchorX, anchorY, anchorZ, rootMatrix, output, warnings, pivot);
    }

    private void loadLayer(Path directory, String world, double anchorX, double anchorY, double anchorZ,
                           Matrix4f layerMatrix, List<ElementSpec> output, List<String> warnings, RotationPivot pivot)
            throws IOException, ConfigReadException
    {
        List<Path> children;
        try (var stream = Files.list(directory))
        {
            children = stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }

        for (Path child : children)
        {
            if (!Files.isRegularFile(child) || !isYaml(child) || UiConfigCompleter.isSettings(child))
            {
                continue;
            }
            try
            {
                ElementSpec element = loadElement(child, world, anchorX, anchorY, anchorZ, layerMatrix, warnings, pivot);
                if (element != null)
                {
                    output.add(element);
                }
            }
            catch (IllegalArgumentException exception)
            {
                warnings.add(relative(child) + ": " + exception.getMessage());
            }
        }

        for (Path child : children)
        {
            if (!Files.isDirectory(child))
            {
                continue;
            }
            YamlConfiguration settings = loadSettings(child);
            if (!settings.getBoolean("enabled", true))
            {
                continue;
            }
            Matrix4f childMatrix = readLayerTransform(settings).applyTo(layerMatrix, pivot);
            loadLayer(child, world, anchorX, anchorY, anchorZ, childMatrix, output, warnings, pivot);
        }
    }

    private ElementSpec loadElement(Path file, String world, double anchorX, double anchorY, double anchorZ,
                                    Matrix4f parent, List<String> warnings, RotationPivot pivot) throws ConfigReadException
    {
        YamlConfiguration yaml = loadYaml(file);
        if (!yaml.getBoolean("visible", true))
        {
            return null;
        }

        ElementType type = ElementType.parse(yaml.getString("type", "text"));
        Vec3 offset = vec(yaml, "offset", Vec3.ZERO);
        Vec3 rotation = vec(yaml, "rotation", Vec3.ZERO);
        Vec3 scale = vec(yaml, "scale", Vec3.ONE);
        int zIndex = yaml.getInt("z-index", 0);
        float zStep = (float) finite(configuration.get().getDouble("render.z-step", 0.0001), 0.0001);

        Matrix4f matrix = new LayerTransform(
                new Vec3(offset.x(), offset.y(), offset.z() + zIndex * zStep), rotation, scale)
                .applyTo(parent, pivot);

        String id = relative(file).replace('\\', '/');
        if (type == ElementType.BLOCK)
        {
            String data = yaml.getString("block.data", "minecraft:stone");
            BlockData block;
            try
            {
                block = blockParser.apply(data == null ? "" : data.trim());
            }
            catch (IllegalArgumentException exception)
            {
                throw new IllegalArgumentException("block.data 不是有效方块状态: " + data
                        + " (" + exception.getMessage() + ")", exception);
            }
            // BlockDisplay's model occupies [0,1]^3; use its cube center as the node pivot.
            matrix.translate(-0.5f, -0.5f, -0.5f);
            return new ElementSpec(id, world, anchorX, anchorY, anchorZ, type, matrix,
                    Component.empty(), 255, TextDisplay.TextAlignment.CENTER, 200,
                    false, false, Color.fromARGB(0, 0, 0, 0), null, block);
        }
        if (type == ElementType.RECTANGLE)
        {
            float width = positive((float) yaml.getDouble("rectangle.width", 1.0), "rectangle.width");
            float height = positive((float) yaml.getDouble("rectangle.height", 1.0), "rectangle.height");
            Color color = readColor(yaml, "rectangle.argb", Color.WHITE);
            matrix.translate(-width / 2.0f, -height / 2.0f, 0.0f)
                    .scale(width, height, 1.0f)
                    .mul(new Matrix4f(UNIT_RECTANGLE));
            return new ElementSpec(id, world, anchorX, anchorY, anchorZ, type, matrix,
                    Component.space(), 255, TextDisplay.TextAlignment.CENTER, 200,
                    false, false, Color.fromARGB(0, 0, 0, 0), color, null);
        }

        float characterSize = (float) yaml.getDouble("text.character-size", 1.0);
        float sizeOffset = (float) yaml.getDouble("text.character-size-offset", 0.0);
        float finalSize = positive(characterSize + sizeOffset,
                "text.character-size + text.character-size-offset");
        matrix.scale(finalSize, finalSize, finalSize);
        String text = yaml.getString("text.content", "");
        String font = yaml.getString("text.font", "minecraft:default");
        List<String> textWarnings = new ArrayList<>();
        Component component = textParser.parse(text, parseFont(font), textWarnings);
        for (String warning : textWarnings)
        {
            warnings.add(id + ": " + warning);
        }
        int opacity = clamp(yaml.getInt("text.opacity", 255), 0, 255);
        int lineWidth = Math.max(1, yaml.getInt("text.line-width", 200));
        TextDisplay.TextAlignment alignment = parseAlignment(yaml.getString("text.alignment", "center"));
        Color background = readColor(yaml, "text.background-argb", Color.fromARGB(0, 0, 0, 0));
        return new ElementSpec(id, world, anchorX, anchorY, anchorZ, type, matrix,
                component, opacity, alignment, lineWidth,
                yaml.getBoolean("text.shadowed", false),
                yaml.getBoolean("text.see-through", false),
                background, null, null);
    }

    private YamlConfiguration loadSettings(Path directory) throws ConfigReadException
    {
        Path canonical = directory.resolve(UiConfigCompleter.SETTINGS_FILE);
        Path alias = directory.resolve(UiConfigCompleter.SETTINGS_FILE_ALIAS);
        return loadYaml(Files.exists(canonical) ? canonical : alias);
    }

    private YamlConfiguration loadYaml(Path file) throws ConfigReadException
    {
        YamlConfiguration yaml = new YamlConfiguration();
        try
        {
            yaml.load(file.toFile());
            return yaml;
        }
        catch (IOException | InvalidConfigurationException exception)
        {
            throw new ConfigReadException(relative(file) + " 解析失败，当前展示保持不变: "
                    + exception.getMessage());
        }
    }

    private LayerTransform readLayerTransform(YamlConfiguration yaml)
    {
        return new LayerTransform(
                vec(yaml, "offset", Vec3.ZERO),
                vec(yaml, "rotation", Vec3.ZERO),
                vec(yaml, "scale", Vec3.ONE),
                vec(yaml, "offset.rotation", Vec3.ZERO)
        );
    }

    private Vec3 vec(YamlConfiguration yaml, String path, Vec3 fallback)
    {
        return new Vec3(
                finiteFloat(yaml.getDouble(path + ".x", fallback.x()), fallback.x()),
                finiteFloat(yaml.getDouble(path + ".y", fallback.y()), fallback.y()),
                finiteFloat(yaml.getDouble(path + ".z", fallback.z()), fallback.z())
        );
    }

    private Color readColor(YamlConfiguration yaml, String path, Color fallback)
    {
        List<?> values = yaml.getList(path);
        if (values != null && values.size() >= 4)
        {
            return Color.fromARGB(
                    colorPart(values.get(0), fallback.getAlpha()),
                    colorPart(values.get(1), fallback.getRed()),
                    colorPart(values.get(2), fallback.getGreen()),
                    colorPart(values.get(3), fallback.getBlue())
            );
        }
        String raw = yaml.getString(path);
        if (raw != null)
        {
            String value = raw.trim().replace("#", "").replace("0x", "").replace("0X", "");
            try
            {
                long parsed = Long.parseUnsignedLong(value, 16);
                if (value.length() <= 6)
                {
                    parsed |= 0xFF000000L;
                }
                return Color.fromARGB((int) parsed);
            }
            catch (IllegalArgumentException ignored)
            {
                throw new IllegalArgumentException(path + " 必须为 [A, R, G, B] 或 AARRGGBB");
            }
        }
        return fallback;
    }

    private int colorPart(Object value, int fallback)
    {
        if (value instanceof Number number)
        {
            return clamp(number.intValue(), 0, 255);
        }
        try
        {
            return clamp(Integer.parseInt(String.valueOf(value)), 0, 255);
        }
        catch (NumberFormatException ignored)
        {
            return fallback;
        }
    }

    private TextDisplay.TextAlignment parseAlignment(String raw)
    {
        String value = raw == null ? "center" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value)
        {
            case "left", "左" -> TextDisplay.TextAlignment.LEFT;
            case "right", "右" -> TextDisplay.TextAlignment.RIGHT;
            case "center", "centre", "中", "居中" -> TextDisplay.TextAlignment.CENTER;
            default -> throw new IllegalArgumentException("text.alignment 只能是 left、center 或 right");
        };
    }

    private Key parseFont(String raw)
    {
        String value = raw == null || raw.isBlank() ? "minecraft:default" : raw.trim();
        try
        {
            return Key.key(value);
        }
        catch (IllegalArgumentException exception)
        {
            throw new IllegalArgumentException("text.font 不是有效字体键: " + value);
        }
    }

    private float positive(float value, String path)
    {
        if (!Float.isFinite(value) || value <= 0.0f)
        {
            throw new IllegalArgumentException(path + " 必须大于 0");
        }
        return value;
    }

    private double finite(double value, double fallback)
    {
        return Double.isFinite(value) ? value : fallback;
    }

    private float finiteFloat(double value, float fallback)
    {
        return Double.isFinite(value) ? (float) value : fallback;
    }

    private int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isYaml(Path path)
    {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private String relative(Path file)
    {
        try
        {
            return uiDirectory.relativize(file).toString();
        }
        catch (IllegalArgumentException ignored)
        {
            return file.toString();
        }
    }

    private static final class ConfigReadException extends Exception
    {
        private ConfigReadException(String message)
        {
            super(message);
        }
    }
}
