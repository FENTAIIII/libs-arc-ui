package com.fentai.libsarcui.model;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public record LayerTransform(Vec3 offset, Vec3 rotation, Vec3 scale, Vec3 offsetRotation)
{
    public LayerTransform(Vec3 offset, Vec3 rotation, Vec3 scale)
    {
        this(offset, rotation, scale, Vec3.ZERO);
    }

    /** Offset rotation changes only T's vector; the outer rotation still follows the selected pivot. */
    public Matrix4f applyTo(Matrix4f parent, RotationPivot pivot)
    {
        Vector3f displacement = new Vector3f(offset.x(), offset.y(), offset.z());
        Matrix4f orbit = new Matrix4f();
        rotate(orbit, offsetRotation);
        orbit.transformDirection(displacement);
        Matrix4f matrix = new Matrix4f(parent);
        if (pivot == RotationPivot.PARENT) rotate(matrix, rotation);
        matrix.translate(displacement);
        if (pivot == RotationPivot.SELF) rotate(matrix, rotation);
        return matrix.scale(nonZero(scale.x()), nonZero(scale.y()), nonZero(scale.z()));
    }

    private static void rotate(Matrix4f matrix, Vec3 rotation)
    {
        matrix.rotateX((float) Math.toRadians(rotation.x()));
        matrix.rotateY((float) Math.toRadians(rotation.y()));
        matrix.rotateZ((float) Math.toRadians(rotation.z()));
    }

    private static float nonZero(float value)
    {
        if (!Float.isFinite(value)) return 1.0f;
        return Math.abs(value) < 0.000001f ? 0.000001f : value;
    }
}
