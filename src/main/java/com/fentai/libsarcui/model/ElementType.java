package com.fentai.libsarcui.model;

import java.util.Locale;

public enum ElementType
{
    TEXT,
    RECTANGLE,
    BLOCK;

    public static ElementType parse(String raw)
    {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized)
        {
            case "text", "文字" -> TEXT;
            case "rectangle", "rect", "color", "color-block", "block-color", "色块", "矩形" -> RECTANGLE;
            case "block" -> BLOCK;
            default -> throw new IllegalArgumentException("未知元素 type: " + raw);
        };
    }
}
