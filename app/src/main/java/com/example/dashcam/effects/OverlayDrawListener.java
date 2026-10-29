package com.example.dashcam.effects;

/** Слушатель отрисовки метки на кадре (вызывается на GL-потоке эффекта). */
public interface OverlayDrawListener {

    /**
     * Вызывается перед отрисовкой кадра. Чтобы нарисовать метку, возьмите
     * {@link OverlayFrame#getOverlayCanvas()} и верните {@code true}. Возврат {@code false}
     * означает, что кадр не нужно рисовать.
     */
    boolean onDraw(OverlayFrame frame);
}
