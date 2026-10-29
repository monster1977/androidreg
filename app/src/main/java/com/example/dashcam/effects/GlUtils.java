/*
 * Портировано из androidx.camera.effects.opengl.Utils (Apache License 2.0).
 * Нужно, чтобы добавить свою яркость в шейдер, который CameraX не даёт расширить.
 */
package com.example.dashcam.effects;

import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Утилиты OpenGL для эффекта яркости и метки. */
final class GlUtils {

    private static final String TAG = "GlUtils";
    private static final int SIZEOF_FLOAT = 4;

    private GlUtils() {
    }

    static void checkEglErrorOrLog(String op) {
        try {
            checkEglErrorOrThrow(op);
        } catch (IllegalStateException e) {
            Log.e(TAG, e.toString(), e);
        }
    }

    static void drawArrays(int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glScissor(0, 0, width, height);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, GlProgram.VERTEX_SIZE);
        checkGlErrorOrThrow("glDrawArrays");
    }

    static FloatBuffer createFloatBuffer(float[] coords) {
        ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * SIZEOF_FLOAT);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(coords);
        fb.position(0);
        return fb;
    }

    static void checkGlErrorOrThrow(String op) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(op + ": GL error 0x" + Integer.toHexString(error));
        }
    }

    static void checkEglErrorOrThrow(String op) {
        int error = EGL14.eglGetError();
        if (error != EGL14.EGL_SUCCESS) {
            throw new IllegalStateException(op + ": EGL error: 0x" + Integer.toHexString(error));
        }
    }

    static void checkLocationOrThrow(int location, String label) {
        if (location < 0) {
            throw new IllegalStateException("Unable to locate '" + label + "' in program");
        }
    }

    /** Создаёт один идентификатор текстуры. */
    static int createTextureId() {
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        checkGlErrorOrThrow("glGenTextures");
        return textureIds[0];
    }

    /** Создаёт один FBO. */
    static int createFbo() {
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        checkGlErrorOrThrow("glGenFramebuffers");
        return fbos[0];
    }

    /** Настраивает текстуру как обычную 2D. */
    static void configureTexture2D(int textureId) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
    }

    /** Настраивает текстуру как внешнюю (OES). */
    static void configureExternalTexture(int textureId) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        checkGlErrorOrThrow("glBindTexture " + textureId);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        checkGlErrorOrThrow("glTexParameter");
    }
}
