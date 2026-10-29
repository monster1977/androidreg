/*
 * Упрощённый аналог androidx.camera.effects.Frame (Apache License 2.0):
 * только те данные, что нужны приложению для отрисовки метки, плюс Canvas метки.
 */
package com.example.dashcam.effects;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Size;
import android.view.Surface;

/** Кадр перед отрисовкой: геометрия для позиционирования метки и Canvas для рисования. */
public final class OverlayFrame {

    private final Surface mOverlaySurface;
    private final long mTimestampNanos;
    private final Size mSize;
    private final Matrix mSensorToBufferTransform;
    private final Rect mCropRect;
    private final int mRotationDegrees;
    private final boolean mMirroring;

    private Canvas mOverlayCanvas;

    private OverlayFrame(Surface overlaySurface, long timestampNanos, Size size,
            Matrix sensorToBufferTransform, Rect cropRect, int rotationDegrees,
            boolean mirroring) {
        mOverlaySurface = overlaySurface;
        mTimestampNanos = timestampNanos;
        mSize = size;
        mSensorToBufferTransform = sensorToBufferTransform;
        mCropRect = cropRect;
        mRotationDegrees = rotationDegrees;
        mMirroring = mirroring;
    }

    /**
     * Создаёт кадр. Геометрия берётся из {@code TransformationInfo}, как в OverlayEffect.
     */
    static OverlayFrame of(Surface overlaySurface, long timestampNanos, Size size,
            android.graphics.Rect cropRect, int rotationDegrees, boolean mirroring,
            Matrix sensorToBufferTransform) {
        return new OverlayFrame(overlaySurface, timestampNanos, size, sensorToBufferTransform,
                cropRect, rotationDegrees, mirroring);
    }

    /** Преобразование из координат сенсора в координаты буфера. */
    public Matrix getSensorToBufferTransform() {
        return mSensorToBufferTransform;
    }

    /** Разрешение кадра. */
    public Size getSize() {
        return mSize;
    }

    /** Прямоугольник кадрирования (область валидных пикселей). */
    public Rect getCropRect() {
        return mCropRect;
    }

    /** Поворот по часовой стрелке в градусах. */
    public int getRotationDegrees() {
        return mRotationDegrees;
    }

    /** Зеркалится ли буфер по вертикальной оси. */
    public boolean isMirroring() {
        return mMirroring;
    }

    /** Метка времени кадра в наносекундах. */
    public long getTimestampNanos() {
        return mTimestampNanos;
    }

    /** Canvas для рисования метки поверх кадра. */
    public Canvas getOverlayCanvas() {
        if (mOverlayCanvas == null) {
            mOverlayCanvas = mOverlaySurface.lockCanvas(null);
        }
        return mOverlayCanvas;
    }

    /** Был ли Canvas метки тронут (то есть нужно ли его публиковать). */
    boolean isOverlayDirty() {
        return mOverlayCanvas != null;
    }
}
