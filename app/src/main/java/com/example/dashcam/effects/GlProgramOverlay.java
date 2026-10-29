/*
 * Портировано из androidx.camera.effects.opengl.GlProgramOverlay (Apache License 2.0).
 * Изменения: униформа uBrightness — множитель яркости входного кадра; мягкое «плечо»
 * (tone mapping) выше 0.9, чтобы усиление не выжигало светлые участки в белое.
 * Метка накладывается после осветления, поэтому текст остаётся читаемым.
 */
package com.example.dashcam.effects;

import static com.example.dashcam.effects.GlUtils.checkGlErrorOrThrow;
import static com.example.dashcam.effects.GlUtils.checkLocationOrThrow;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

/** Программа, которая копирует кадр (с усилением яркости) и накладывает метку. */
class GlProgramOverlay extends GlProgram {

    private static final String TEXTURE_MATRIX = "uTexMatrix";
    private static final String OVERLAY_SAMPLER = "samplerOverlayTexture";
    private static final String BRIGHTNESS_UNIFORM = "uBrightness";

    private static final String VERTEX_SHADER = "uniform mat4 " + TEXTURE_MATRIX + ";\n"
            + "attribute vec4 " + POSITION_ATTRIBUTE + ";\n"
            + "attribute vec4 " + TEXTURE_ATTRIBUTE + ";\n"
            + "varying vec2 " + TEXTURE_COORDINATES + ";\n"
            + "void main() {\n"
            + "    gl_Position = " + POSITION_ATTRIBUTE + ";\n"
            + "    " + TEXTURE_COORDINATES + " = (" + TEXTURE_MATRIX + " * "
            + TEXTURE_ATTRIBUTE + ").xy;\n"
            + "}";

    private static final String FRAGMENT_SHADER = ""
            + "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 " + TEXTURE_COORDINATES + ";\n"
            + "uniform samplerExternalOES " + INPUT_SAMPLER + ";\n"
            + "uniform samplerExternalOES " + OVERLAY_SAMPLER + ";\n"
            + "uniform float " + BRIGHTNESS_UNIFORM + ";\n"
            + "void main() {\n"
            + "    vec4 inputColor = texture2D(" + INPUT_SAMPLER + ", "
            + TEXTURE_COORDINATES + ");\n"
            + "    vec4 overlayColor = texture2D(" + OVERLAY_SAMPLER + ", "
            + TEXTURE_COORDINATES + ");\n"
            + "    vec3 lit = inputColor.rgb * " + BRIGHTNESS_UNIFORM + ";\n"
            + "    // Мягкое «плечо»: до 0.9 линейно, выше — плавно к 1.0.\n"
            + "    // Так осветление не выжигает белое (фары, снег, номера) в сплошные 255.\n"
            + "    vec3 over = max(lit - vec3(0.9), vec3(0.0));\n"
            + "    lit = min(lit, vec3(0.9)) + over / (vec3(1.0) + over / vec3(0.1));\n"
            + "    inputColor.rgb = lit;\n"
            + "    gl_FragColor = inputColor * (1.0 - overlayColor.a) + overlayColor;\n"
            + "}";

    private int mTextureMatrixLoc = -1;
    private int mBrightnessLoc = -1;

    GlProgramOverlay() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    protected void configure() {
        super.configure();
        int inputSamplerLoc = GLES20.glGetUniformLocation(mProgramHandle, INPUT_SAMPLER);
        checkLocationOrThrow(inputSamplerLoc, INPUT_SAMPLER);
        GLES20.glUniform1i(inputSamplerLoc, 0);

        int overlaySamplerLoc = GLES20.glGetUniformLocation(mProgramHandle, OVERLAY_SAMPLER);
        checkLocationOrThrow(overlaySamplerLoc, OVERLAY_SAMPLER);
        GLES20.glUniform1i(overlaySamplerLoc, 1);

        mTextureMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, TEXTURE_MATRIX);
        checkLocationOrThrow(mTextureMatrixLoc, TEXTURE_MATRIX);

        mBrightnessLoc = GLES20.glGetUniformLocation(mProgramHandle, BRIGHTNESS_UNIFORM);
        checkLocationOrThrow(mBrightnessLoc, BRIGHTNESS_UNIFORM);
        GLES20.glUniform1f(mBrightnessLoc, 1.0f);
    }

    @Override
    protected void release() {
        super.release();
        mTextureMatrixLoc = -1;
        mBrightnessLoc = -1;
    }

    /**
     * Рисует входную текстуру на Surface, накладывая метку.
     *
     * @param inputTextureId   идентификатор входной внешней текстуры.
     * @param overlayTextureId идентификатор внешней текстуры метки.
     * @param matrix           матрица преобразования текстурных координат.
     * @param glContext        GL-контекст с EGLSurface нужного Surface.
     * @param surface          Surface, куда рисуем.
     * @param timestampNs      метка времени кадра в наносекундах.
     * @param brightness       множитель яркости кадра (1.0 — без изменений).
     */
    void draw(int inputTextureId, int overlayTextureId, float[] matrix,
            GlContext glContext, Surface surface, long timestampNs, float brightness) {
        use();
        uploadParameters(inputTextureId, overlayTextureId, matrix, brightness);
        try {
            glContext.drawAndSwap(surface, timestampNs);
        } catch (IllegalStateException e) {
            android.util.Log.w("GlProgramOverlay", "Failed to draw the frame", e);
        }
    }

    private void uploadParameters(int inputTextureId, int overlayTextureId, float[] matrix,
            float brightness) {
        GLES20.glUniformMatrix4fv(mTextureMatrixLoc, 1, false, matrix, 0);
        checkGlErrorOrThrow("glUniformMatrix4fv");

        GLES20.glUniform1f(mBrightnessLoc, brightness);
        checkGlErrorOrThrow("glUniform1f");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId);
        checkGlErrorOrThrow("glBindTexture");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, overlayTextureId);
        checkGlErrorOrThrow("glBindTexture");
    }
}
