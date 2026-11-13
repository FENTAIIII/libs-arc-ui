package com.fentai.libsarcui.model;

public record Vec3(float x, float y, float z)
{
    public static final Vec3 ZERO = new Vec3(0.0f, 0.0f, 0.0f);
    public static final Vec3 ONE = new Vec3(1.0f, 1.0f, 1.0f);
}
