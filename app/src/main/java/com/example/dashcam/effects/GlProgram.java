/*
 * Портировано из androidx.camera.effects.opengl.GlProgram (Apache License 2.0).
 */
package com.example.dashcam.effects;

import static com.example.dashcam.effects.GlUtils.checkGlErrorOrThrow;
import static com.example.dashcam.effects.GlUtils.checkLocationOrThrow;
import static com.example.dashcam.effects.GlUtils.createFloatBuffer;

import android.opengl.GLES20;
import android.util.Log;

import java.nio.FloatBuffer;

/** Базовый класс программы OpenGL. */
abstract class GlProgram {

    private static final String TAG = "GlProgram";

    static final String POSITION_ATTRIBUTE = "aPosition";
    static final String TEXTURE_ATTRIBUTE = "aTextureCoord";
    static final String TEXTURE_COORDINATES = "vTextureCoord";
    static final String INPUT_SAMPLER = "samplerInputTexture";

    // Используется с POSITION_ATTRIBUTE.
    private static final FloatBuffer VERTEX_BUFFER = createFloatBuffer(new float[]{
            -1.0f, -1.0f, // 0 левый низ
            1.0f, -1.0f,  // 1 правый низ
            -1.0f, 1.0f,  // 2 левый верх
            1.0f, 1.0f    // 3 правый верх
    });

    // Используется с TEXTURE_ATTRIBUTE.
    private static final FloatBuffer TEXTURE_BUFFER = createFloatBuffer(new float[]{
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
    });

    /** Размер VERTEX_BUFFER и TEXTURE_BUFFER. */
    static final int VERTEX_SIZE = 4;

    int mProgramHandle = -1;

    private final String mVertexShader;
    private final String mFragmentShader;

    GlProgram(String vertexShader, String fragmentShader) {
        mVertexShader = vertexShader;
        mFragmentShader = fragmentShader;
    }

    /** Инициализирует программу. */
    void init() {
        if (mProgramHandle != -1) {
            throw new IllegalStateException("Program already initialized.");
        }
        mProgramHandle = createProgram(mVertexShader, mFragmentShader);
        use();
        configure();
    }

    /** Настраивает общие атрибуты; наследники добавляют свои. */
    protected void configure() {
        checkInitialized();

        int positionLoc = GLES20.glGetAttribLocation(mProgramHandle, POSITION_ATTRIBUTE);
        checkLocationOrThrow(positionLoc, POSITION_ATTRIBUTE);
        GLES20.glEnableVertexAttribArray(positionLoc);
        checkGlErrorOrThrow("glEnableVertexAttribArray");
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, VERTEX_BUFFER);
        checkGlErrorOrThrow("glVertexAttribPointer");

        int texCoordLoc = GLES20.glGetAttribLocation(mProgramHandle, TEXTURE_ATTRIBUTE);
        checkLocationOrThrow(texCoordLoc, TEXTURE_ATTRIBUTE);
        GLES20.glEnableVertexAttribArray(texCoordLoc);
        checkGlErrorOrThrow("glEnableVertexAttribArray");
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, TEXTURE_BUFFER);
        checkGlErrorOrThrow("glVertexAttribPointer");
    }

    /** Делает программу текущей. */
    protected final void use() {
        checkInitialized();
        GLES20.glUseProgram(mProgramHandle);
        checkGlErrorOrThrow("glUseProgram");
    }

    /** Удаляет программу и освобождает ресурсы. */
    protected void release() {
        if (isInitialized()) {
            GLES20.glDeleteProgram(mProgramHandle);
            checkGlErrorOrThrow("glDeleteProgram");
            mProgramHandle = -1;
        }
    }

    private void checkInitialized() {
        if (!isInitialized()) {
            throw new IllegalStateException("Program not initialized");
        }
    }

    private boolean isInitialized() {
        return mProgramHandle != -1;
    }

    private int createProgram(String vertexShaderStr, String fragmentShaderStr) {
        int vertexShader = -1, fragmentShader = -1, program = -1;
        try {
            program = GLES20.glCreateProgram();
            checkGlErrorOrThrow("glCreateProgram");

            vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderStr);
            GLES20.glAttachShader(program, vertexShader);
            checkGlErrorOrThrow("glAttachShader");

            fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderStr);
            GLES20.glAttachShader(program, fragmentShader);
            checkGlErrorOrThrow("glAttachShader");

            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                throw new IllegalStateException(
                        "Could not link program: " + GLES20.glGetProgramInfoLog(program));
            }
            return program;
        } catch (IllegalStateException | IllegalArgumentException e) {
            if (vertexShader != -1) {
                GLES20.glDeleteShader(vertexShader);
            }
            if (fragmentShader != -1) {
                GLES20.glDeleteShader(fragmentShader);
            }
            if (program != -1) {
                GLES20.glDeleteProgram(program);
            }
            throw e;
        }
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        checkGlErrorOrThrow("glCreateShader type=" + shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.w(TAG, "Could not compile shader: " + source);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException(
                    "Could not compile shader type " + shaderType + ":"
                            + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
