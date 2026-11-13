package com.fentai.libsarcui.model;

import java.util.Locale;

public enum RotationPivot
{
    SELF,
    PARENT;

    public static RotationPivot parse(String value)
    {
        return switch (value == null ? "self" : value.trim().toLowerCase(Locale.ROOT))
        {
            case "self" -> SELF;
            case "parent" -> PARENT;
            default -> throw new IllegalArgumentException("editing.rotation-pivot 只能是 self 或 parent");
        };
    }
}
