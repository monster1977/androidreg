/*
 * Портировано из androidx.camera.effects.opengl.GlContext (Apache License 2.0).
 * Logger заменён на android.util.Log, аннотации убраны.
 */
package com.example.dashcam.effects;

import static com.example.dashcam.effects.GlUtils.checkEglErrorOrLog;
import static com.example.dashcam.effects.GlUtils.checkEglErrorOrThrow;
import static com.example.dashcam.effects.GlUtils.drawArrays;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.util.Log;
import android.view.Surface;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Управляет конфигурациями OpenGL.
 *
 * <p>Позволяет регистрировать/снимать выходные Surface и связанные с ними EGLSurface.
 */
final class GlContext {

    private static final String TAG = "GlContext";

    // EGL.
    private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig mEglConfig = null;

    // Текущий выходной Surface.
    private EglSurface mCurrentSurface = null;
    // Временный выходной Surface, пока ничего не зарегистрировано.
    private EglSurface mTempSurface = null;
    private final Map<Surface, EglSurface> mRegisteredSurfaces = new HashMap<>();

    private Thread mGlThread;

    void init() {
        checkState(Objects.equals(mEglDisplay, EGL14.EGL_NO_DISPLAY), "Already initialized");
        mGlThread = Thread.currentThread();

        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (Objects.equals(mEglDisplay, EGL14.EGL_NO_DISPLAY)) {
            throw new IllegalStateException("Unable to get EGL14 display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
            throw new IllegalStateException("Unable to initialize EGL14");
        }

        int rgbBits = 8;
        int alphaBits = 8;
        int renderType = EGL14.EGL_OPENGL_ES2_BIT;
        int recordableAndroid = EGL14.EGL_TRUE;
        int[] attribToChooseConfig = {
                EGL14.EGL_RED_SIZE, rgbBits,
                EGL14.EGL_GREEN_SIZE, rgbBits,
                EGL14.EGL_BLUE_SIZE, rgbBits,
                EGL14.EGL_ALPHA_SIZE, alphaBits,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, renderType,
                EGLExt.EGL_RECORDABLE_ANDROID, recordableAndroid,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(
                mEglDisplay, attribToChooseConfig, 0, configs, 0, configs.length, numConfigs, 0
        )) {
            throw new IllegalStateException("Unable to find a suitable EGLConfig");
        }
        mEglConfig = configs[0];
        int[] attribToCreateContext = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };

        mEglContext = EGL14.eglCreateContext(
                mEglDisplay, mEglConfig, EGL14.EGL_NO_CONTEXT,
                attribToCreateContext, 0
        );
        checkEglErrorOrThrow("eglCreateContext");
        int[] values = new int[1];
        EGL14.eglQueryContext(
                mEglDisplay, mEglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, values, 0
        );
        Log.d(TAG, "EGLContext created, client version " + values[0]);

        mTempSurface = create1x1PBufferSurface();
        makeCurrent(mTempSurface);
    }

    /** Регистрирует Surface как выходной. */
    void registerSurface(Surface surface) {
        checkInitialized();
        checkGlThread();
        if (!mRegisteredSurfaces.containsKey(surface)) {
            mRegisteredSurfaces.put(surface, null);
        }
    }

    /** Снимает регистрацию выходного Surface. */
    void unregisterSurface(Surface surface) {
        checkInitialized();
        checkGlThread();
        if (mCurrentSurface != null && mCurrentSurface.getSurface() == surface) {
            makeCurrent(mTempSurface);
        }
        EglSurface removedSurface = mRegisteredSurfaces.remove(surface);
        if (removedSurface != null) {
            destroyEglSurface(removedSurface);
        }
    }

    /**
     * Рисует текущую связанную текстуру на заданный Surface.
     *
     * @param timestampNs метка времени кадра в наносекундах.
     */
    void drawAndSwap(Surface surface, long timestampNs) {
        checkInitialized();
        checkGlThread();
        checkState(mRegisteredSurfaces.containsKey(surface), "The Surface is not registered.");

        EglSurface eglSurface = mRegisteredSurfaces.get(surface);
        if (eglSurface == null) {
            eglSurface = createEglSurface(surface);
            if (eglSurface == null) {
                Log.w(TAG, "Failed to create EGLSurface. Skip drawing.");
                return;
            }
            mRegisteredSurfaces.put(surface, eglSurface);
        }

        makeCurrent(eglSurface);
        drawArrays(eglSurface.getWidth(), eglSurface.getHeight());
        EGLExt.eglPresentationTimeANDROID(mEglDisplay, eglSurface.getEglSurface(), timestampNs);

        if (!EGL14.eglSwapBuffers(mEglDisplay, eglSurface.getEglSurface())) {
            Log.w(TAG, "Failed to swap buffers with EGL error: 0x"
                    + Integer.toHexString(EGL14.eglGetError()));
            unregisterSurface(surface);
            mRegisteredSurfaces.put(surface, null);
        }
    }

    void release() {
        checkGlThread();
        if (!Objects.equals(mEglDisplay, EGL14.EGL_NO_DISPLAY)) {
            EGL14.eglMakeCurrent(
                    mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
            );
        }

        for (EglSurface eglSurface : mRegisteredSurfaces.values()) {
            if (eglSurface != null) {
                destroyEglSurface(eglSurface);
            }
        }
        mRegisteredSurfaces.clear();

        if (mTempSurface != null) {
            destroyEglSurface(mTempSurface);
            mTempSurface = null;
        }
        mCurrentSurface = null;

        if (!Objects.equals(mEglContext, EGL14.EGL_NO_CONTEXT)) {
            EGL14.eglDestroyContext(mEglDisplay, mEglContext);
            mEglContext = EGL14.EGL_NO_CONTEXT;
        }
        if (!Objects.equals(mEglDisplay, EGL14.EGL_NO_DISPLAY)) {
            EGL14.eglTerminate(mEglDisplay);
            mEglDisplay = EGL14.EGL_NO_DISPLAY;
        }

        EGL14.eglReleaseThread();
        mEglConfig = null;
    }

    void checkGlThread() {
        checkState(Objects.equals(mGlThread, Thread.currentThread()),
                "Must be called from the same thread that initializes this GlContext");
    }

    // --- Приватное ---

    private void destroyEglSurface(EglSurface eglSurface) {
        if (!EGL14.eglDestroySurface(mEglDisplay, eglSurface.getEglSurface())) {
            checkEglErrorOrLog("eglDestroySurface");
        }
    }

    private EglSurface createEglSurface(Surface surface) {
        EGLSurface eglSurface;
        try {
            int[] surfaceAttrib = {
                    EGL14.EGL_NONE
            };
            eglSurface = EGL14.eglCreateWindowSurface(
                    mEglDisplay, mEglConfig, surface, surfaceAttrib, 0);
            checkEglErrorOrThrow("eglCreateWindowSurface");
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "Failed to create EGL surface: " + e.getMessage(), e);
            return null;
        }
        int width = querySurface(eglSurface, EGL14.EGL_WIDTH);
        int height = querySurface(eglSurface, EGL14.EGL_HEIGHT);
        return EglSurface.of(eglSurface, surface, width, height);
    }

    private int querySurface(EGLSurface eglSurface, int what) {
        int[] value = new int[1];
        EGL14.eglQuerySurface(mEglDisplay, eglSurface, what, value, 0);
        return value[0];
    }

    private void makeCurrent(EglSurface eglSurface) {
        checkInitialized();
        if (!EGL14.eglMakeCurrent(mEglDisplay, eglSurface.getEglSurface(),
                eglSurface.getEglSurface(), mEglContext)) {
            throw new IllegalStateException(
                    "eglMakeCurrent failed. GL Error: " + EGL14.eglGetError());
        }
        mCurrentSurface = eglSurface;
    }

    private void checkInitialized() {
        checkState(isInitialized(), "GlContext is not initialized");
    }

    private boolean isInitialized() {
        return !Objects.equals(mEglDisplay, EGL14.EGL_NO_DISPLAY)
                && !Objects.equals(mEglContext, EGL14.EGL_NO_CONTEXT)
                && mEglConfig != null;
    }

    private EglSurface create1x1PBufferSurface() {
        int width = 1;
        int height = 1;
        int[] surfaceAttrib = {
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
        };
        EGLSurface eglSurface = EGL14.eglCreatePbufferSurface(mEglDisplay, mEglConfig,
                surfaceAttrib, 0);
        checkEglErrorOrThrow("eglCreatePbufferSurface");
        if (eglSurface == null) {
            throw new IllegalStateException("surface was null");
        }
        return EglSurface.of(eglSurface, null, width, height);
    }

    private static void checkState(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
