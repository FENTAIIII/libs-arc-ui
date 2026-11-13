package com.fentai.libsarcui.model;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.TextDisplay;
import org.joml.Matrix4f;

public record ElementSpec(
        String id,
        String world,
        double anchorX,
        double anchorY,
        double anchorZ,
        ElementType type,
        Matrix4f transformation,
        Component text,
        int opacity,
        TextDisplay.TextAlignment alignment,
        int lineWidth,
        boolean shadowed,
        boolean seeThrough,
        Color backgroundColor,
        Color rectangleColor,
        BlockData blockData
)
{
}
