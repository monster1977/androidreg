/*
 * Основано на androidx.camera.effects.opengl.GlProgramCopy (Apache License 2.0).
 *
 * Программа просто копирует входной (внешний OES) кадр в маленький FBO, чтобы затем
 * прочитать пиксели и оценить среднюю яркость кадра для автояркости.
 */
package com.example.dashcam.effects;

import static com.example.dashcam.effects.GlUtils.checkGlErrorOrThrow;
import static com.example.dashcam.effects.GlUtils.checkLocationOrThrow;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

/** Копирует входной кадр без изменений (нужен только для замера яркости). */
class GlProgramCopy extends GlProgram {

    private static final String VERTEX_SHADER = "attribute vec4 " + POSITION_ATTRIBUTE + ";\n"
            + "attribute vec4 " + TEXTURE_ATTRIBUTE + ";\n"
            + "varying vec2 " + TEXTURE_COORDINATES + ";\n"
            + "void main() {\n"
            + "    gl_Position = " + POSITION_ATTRIBUTE + ";\n"
            + "    " + TEXTURE_COORDINATES + " = " + TEXTURE_ATTRIBUTE + ".xy;\n"
            + "}";

    private static final String FRAGMENT_SHADER = ""
            + "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 " + TEXTURE_COORDINATES + ";\n"
            + "uniform samplerExternalOES " + INPUT_SAMPLER + ";\n"
            + "void main() {\n"
            + "    gl_FragColor = texture2D(" + INPUT_SAMPLER + ", " + TEXTURE_COORDINATES + ");\n"
            + "}";

    GlProgramCopy() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    protected void configure() {
        super.configure();
        int inputSamplerLoc = GLES20.glGetUniformLocation(mProgramHandle, INPUT_SAMPLER);
        checkLocationOrThrow(inputSamplerLoc, INPUT_SAMPLER);
        GLES20.glUniform1i(inputSamplerLoc, 0);
    }

    /** Рисует входную внешнюю текстуру в заранее привязанный FBO. */
    void draw(int inputTextureId) {
        use();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId);
        checkGlErrorOrThrow("glBindTexture");
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_SIZE);
        checkGlErrorOrThrow("glDrawArrays");
    }
}
