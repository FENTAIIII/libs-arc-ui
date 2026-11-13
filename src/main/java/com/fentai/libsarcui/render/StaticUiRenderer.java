package com.fentai.libsarcui.render;

import com.fentai.libsarcui.LibsArcUiPlugin;
import com.fentai.libsarcui.model.ElementSpec;
import com.fentai.libsarcui.model.ElementType;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Global tasks publish targets; only owning region/entity tasks touch world state. */
public final class StaticUiRenderer
{
    private final LibsArcUiPlugin plugin;
    private final NamespacedKey ownerKey;
    private final NamespacedKey elementKey;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    public StaticUiRenderer(LibsArcUiPlugin plugin)
    {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "managed");
        this.elementKey = new NamespacedKey(plugin, "element");
    }

    public List<String> sync(List<ElementSpec> specs)
    {
        List<String> warnings = new ArrayList<>();
        if (stopped) return warnings;
        RenderOptions options = RenderOptions.read(plugin.getConfig());
        Set<String> expected = new HashSet<>();
        for (ElementSpec spec : specs)
        {
            World world = Bukkit.getWorld(spec.world());
            if (world == null)
            {
                warnings.add(spec.id() + ": 世界不存在或尚未加载: " + spec.world());
                continue;
            }
            expected.add(spec.id());
            Slot slot = slots.computeIfAbsent(spec.id(), Slot::new);
            slot.target = new Target(world, spec, options);
            request(slot);
        }
        for (Slot slot : List.copyOf(slots.values()))
        {
            if (!expected.contains(slot.id))
            {
                slot.target = null;
                request(slot);
                slots.remove(slot.id, slot);
            }
        }
        return warnings;
    }

    private void request(Slot slot)
    {
        if (stopped || !plugin.isEnabled()) return;
        Handle handle = slot.handle;
        if (handle == null)
        {
            requestSpawn(slot);
        }
        else
        {
            handle.entity.getScheduler().run(plugin, task -> update(slot, handle), () ->
            {
                // Retirement callbacks must not touch entities or load chunks.
                synchronized (slot)
                {
                    if (slot.handle == handle) slot.handle = null;
                }
            });
        }
    }

    private void update(Slot slot, Handle handle)
    {
        if (stopped || slot.handle != handle) return;
        Target target = slot.target;
        if (target == null || !handle.entity.isValid() || !handle.anchor.sameAnchor(target)
                || (handle.entity instanceof BlockDisplay) != (target.spec.type() == ElementType.BLOCK))
        {
            handle.entity.remove();
            synchronized (slot)
            {
                if (slot.handle == handle) slot.handle = null;
            }
            // Moving to another world/region removes here and spawns there.
            requestSpawn(slot);
            return;
        }
        apply(handle.entity, target);
    }

    private void requestSpawn(Slot slot)
    {
        Target target;
        synchronized (slot)
        {
            target = slot.target;
            if (stopped || target == null || slot.handle != null || slot.spawnPending) return;
            slot.spawnPending = true;
        }
        // A region task alone does not keep an unloaded chunk alive.
        target.world.getChunkAtAsync(target.chunkX(), target.chunkZ(), true)
                .whenComplete((chunk, failure) ->
                {
                    if (stopped || !plugin.isEnabled()) return;
                    if (failure != null)
                    {
                        synchronized (slot)
                        {
                            slot.spawnPending = false;
                        }
                        plugin.getLogger().warning(slot.id + ": 区块加载失败: " + failure.getMessage());
                        return;
                    }
                    Bukkit.getRegionScheduler().execute(plugin, target.world, target.chunkX(), target.chunkZ(),
                            () -> spawn(slot, target));
                });
    }

    private void spawn(Slot slot, Target requested)
    {
        if (stopped) return;
        synchronized (slot)
        {
            slot.spawnPending = false;
            if (slot.target == requested && slot.handle == null)
            {
                Chunk chunk = requested.world.getChunkAt(requested.chunkX(), requested.chunkZ());
                for (Entity entity : chunk.getEntities())
                {
                    if (isManaged(entity) && slot.id.equals(entity.getPersistentDataContainer()
                            .get(elementKey, PersistentDataType.STRING)))
                    {
                        entity.remove();
                    }
                }
                ElementSpec spec = requested.spec;
                Location anchor = new Location(requested.world, spec.anchorX(), spec.anchorY(), spec.anchorZ());
                Class<? extends Display> displayClass = spec.type() == ElementType.BLOCK
                        ? BlockDisplay.class : TextDisplay.class;
                Display entity = requested.world.spawn(anchor, displayClass, display ->
                {
                    // YAML is the source of truth. Never write these entities to chunk NBT.
                    display.setPersistent(false);
                    display.setGravity(false);
                    display.setInvulnerable(true);
                    display.setSilent(true);
                    display.getPersistentDataContainer().set(ownerKey, PersistentDataType.BYTE, (byte) 1);
                    display.getPersistentDataContainer().set(elementKey, PersistentDataType.STRING, slot.id);
                    apply(display, requested);
                });
                slot.handle = new Handle(entity, requested);
            }
        }
        if (slot.target != requested) request(slot);
    }

    /** Called when the chunk's entity NBT has actually been loaded, on its owning region. */
    public void onEntitiesLoad(Chunk chunk, List<Entity> entities)
    {
        if (stopped) return;
        for (Entity entity : entities)
        {
            if (!isManaged(entity)) continue;
            String id = entity.getPersistentDataContainer().get(elementKey, PersistentDataType.STRING);
            Slot slot = id == null ? null : slots.get(id);
            Handle handle = slot == null ? null : slot.handle;
            if (handle == null || handle.entity != entity)
            {
                // Purge persisted entities from versions 1.0.x and obsolete copies.
                entity.remove();
            }
        }
        for (Slot slot : slots.values())
        {
            Target target = slot.target;
            if (target != null && target.inChunk(chunk)) request(slot);
        }
    }

    public void onEntitiesUnload(List<Entity> entities)
    {
        for (Entity entity : entities)
        {
            if (!isManaged(entity)) continue;
            String id = entity.getPersistentDataContainer().get(elementKey, PersistentDataType.STRING);
            Slot slot = id == null ? null : slots.get(id);
            if (slot == null) continue;
            synchronized (slot)
            {
                if (slot.handle != null && slot.handle.entity == entity) slot.handle = null;
            }
        }
    }

    private boolean isManaged(Entity entity)
    {
        return (entity instanceof TextDisplay || entity instanceof BlockDisplay)
                && entity.getPersistentDataContainer().has(ownerKey, PersistentDataType.BYTE);
    }

    private void apply(Display display, Target target)
    {
        ElementSpec spec = target.spec;
        RenderOptions options = target.options;
        display.setBillboard(Display.Billboard.FIXED);
        display.setBrightness(new Display.Brightness(options.blockLight, options.skyLight));
        display.setViewRange(options.viewRange);
        display.setShadowRadius(options.shadowRadius);
        display.setShadowStrength(options.shadowStrength);
        display.setInterpolationDuration(0);
        display.setInterpolationDelay(0);
        display.setTeleportDuration(0);
        display.setTransformationMatrix(new org.joml.Matrix4f(spec.transformation()));
        if (display instanceof BlockDisplay block)
        {
            block.setBlock(spec.blockData());
        }
        else if (display instanceof TextDisplay text)
        {
            text.setAlignment(spec.alignment());
            text.setLineWidth(spec.lineWidth());
            text.setShadowed(spec.shadowed());
            text.setSeeThrough(spec.seeThrough());
            text.setTextOpacity((byte) spec.opacity());
            text.setDefaultBackground(false);
            text.text(spec.text());
            text.setBackgroundColor(spec.type() == ElementType.RECTANGLE
                    ? spec.rectangleColor() : spec.backgroundColor());
        }
    }

    public void destroy()
    {
        stopped = true;
        for (Slot slot : slots.values())
        {
            slot.target = null;
            Handle handle = slot.handle;
            // Folia has already halted its regions during server shutdown; its ownership
            // check alone is insufficient then. Nonpersistent entities need no save cleanup.
            if (!Bukkit.isStopping() && handle != null && Bukkit.isOwnedByCurrentRegion(handle.entity))
            {
                handle.entity.remove();
            }
        }
        slots.clear();
    }

    private static final class Slot
    {
        private final String id;
        private volatile Target target;
        private volatile Handle handle;
        private boolean spawnPending;
        private Slot(String id)
        {
            this.id = id;
        }
    }

    private record Handle(Display entity, Target anchor)
    {
    }

    private record Target(World world, ElementSpec spec, RenderOptions options)
    {
        private int chunkX()
        {
            return ((int) Math.floor(spec.anchorX())) >> 4;
        }
        private int chunkZ()
        {
            return ((int) Math.floor(spec.anchorZ())) >> 4;
        }
        private boolean inChunk(Chunk chunk)
        {
            return world == chunk.getWorld() && chunkX() == chunk.getX() && chunkZ() == chunk.getZ();
        }
        private boolean sameAnchor(Target other)
        {
            return world == other.world && spec.anchorX() == other.spec.anchorX()
                    && spec.anchorY() == other.spec.anchorY() && spec.anchorZ() == other.spec.anchorZ();
        }
    }

    private record RenderOptions(int blockLight, int skyLight, float viewRange,
                                 float shadowRadius, float shadowStrength)
    {
        private static RenderOptions read(FileConfiguration config)
        {
            return new RenderOptions(Math.max(0, Math.min(15, config.getInt("render.brightness.block", 15))),
                    Math.max(0, Math.min(15, config.getInt("render.brightness.sky", 15))),
                    number(config, "render.view-range", 64.0f, true),
                    number(config, "render.shadow-radius", 0.0f, false),
                    number(config, "render.shadow-strength", 0.0f, false));
        }
        private static float number(FileConfiguration config, String path, float fallback, boolean positive)
        {
            float value = (float) config.getDouble(path, fallback);
            return Float.isFinite(value) && (positive ? value > 0 : value >= 0) ? value : fallback;
        }
    }
}
