/*
 * Портировано из androidx.camera.effects.opengl.EglSurface (Apache License 2.0).
 * AutoValue заменён на обычный неизменяемый класс.
 */
package com.example.dashcam.effects;

import android.opengl.EGLSurface;
import android.view.Surface;

/** Surface вместе с его EGLSurface и размером. */
final class EglSurface {

    private final EGLSurface mEglSurface;
    private final Surface mSurface;
    private final int mWidth;
    private final int mHeight;

    private EglSurface(EGLSurface eglSurface, Surface surface, int width, int height) {
        mEglSurface = eglSurface;
        mSurface = surface;
        mWidth = width;
        mHeight = height;
    }

    static EglSurface of(EGLSurface eglSurface, Surface surface, int width, int height) {
        return new EglSurface(eglSurface, surface, width, height);
    }

    /** EGLSurface, созданный на основе Surface. Если Surface == null — это Pbuffer. */
    EGLSurface getEglSurface() {
        return mEglSurface;
    }

    Surface getSurface() {
        return mSurface;
    }

    int getWidth() {
        return mWidth;
    }

    int getHeight() {
        return mHeight;
    }
}
