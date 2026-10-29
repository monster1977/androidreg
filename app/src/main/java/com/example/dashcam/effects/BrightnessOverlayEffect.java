/*
 * Замена androidx.camera.effects.OverlayEffect (Apache License 2.0).
 * Отличие: помимо наложения метки этот эффект усиливает яркость кадра — на телефонах,
 * где экспокоррекция камеры не работает, это единственный способ осветлить видео.
 */
package com.example.dashcam.effects;

import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraEffect;
import androidx.core.util.Consumer;

/**
 * Эффект, который осветляет кадр (множителем яркости) и вжигает метку приложения
 * в превью и записываемое видео.
 */
public final class BrightnessOverlayEffect extends CameraEffect implements AutoCloseable {

    private final BrightnessOverlayProcessor mProcessor;

    /**
     * @param targets       цели эффекта, например {@link CameraEffect#PREVIEW}
     *                      {@code |} {@link CameraEffect#VIDEO_CAPTURE}.
     * @param handler       Handler для прослушивания входного Surface и операций OpenGL.
     * @param errorListener вызывается при неустранимых ошибках эффекта.
     */
    public BrightnessOverlayEffect(int targets, @NonNull Handler handler,
            @NonNull Consumer<Throwable> errorListener) {
        this(targets, new BrightnessOverlayProcessor(handler), errorListener);
    }

    private BrightnessOverlayEffect(int targets, @NonNull BrightnessOverlayProcessor processor,
            @NonNull Consumer<Throwable> errorListener) {
        super(targets, processor.getGlExecutor(), processor, errorListener);
        mProcessor = processor;
    }

    /** Задаёт слушатель отрисовки метки. */
    public void setOnDrawListener(@NonNull OverlayDrawListener onDrawListener) {
        mProcessor.setOnDrawListener(onDrawListener);
    }

    /** Снимает слушатель отрисовки метки. */
    public void clearOnDrawListener() {
        mProcessor.setOnDrawListener(null);
    }

    /** Включает/выключает цифровое осветление тёмной сцены (по умолчанию выключено). */
    public void setAutoBrightEnabled(boolean enabled) {
        mProcessor.setAutoBrightEnabled(enabled);
    }

    /** Текущий применённый множитель яркости (1.0 — без изменений). */
    public float getBrightness() {
        return mProcessor.getBrightness();
    }

    /** Последняя измеренная средняя яркость кадра (0…255) или -1. */
    public int getLastMeanLuma() {
        return mProcessor.getLastMeanLuma();
    }

    /** Закрывает эффект; после этого он непригоден. */
    @Override
    public void close() {
        mProcessor.release();
    }
}
