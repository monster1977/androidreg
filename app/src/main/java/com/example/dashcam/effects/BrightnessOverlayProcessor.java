/*
 * Портировано из androidx.camera.effects.internal.SurfaceProcessorImpl (Apache License 2.0).
 *
 * Упрощения и изменения:
 *  - поддерживается только очередь глубиной 0 (кадры рисуются сразу, буфер не копируется);
 *  - программа рисования усилена униформой яркости (uBrightness);
 *  - вместо внутреннего GlRenderer/Frame используются собственные GlContext/OverlayFrame.
 */
package com.example.dashcam.effects;

import static com.example.dashcam.effects.GlUtils.checkGlErrorOrThrow;
import static com.example.dashcam.effects.GlUtils.configureExternalTexture;
import static com.example.dashcam.effects.GlUtils.createTextureId;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.camera.core.SurfaceOutput;
import androidx.camera.core.SurfaceProcessor;
import androidx.camera.core.SurfaceRequest;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Реализация {@link SurfaceProcessor}: усиливает яркость входного кадра и накладывает метку,
 * которую приложение рисует через {@link OverlayDrawListener}.
 *
 * <p>Ожидает один входной и один выходной Surface. Все методы выполняются на GL-потоке,
 * который задаётся переданным {@link Handler}.
 */
public final class BrightnessOverlayProcessor implements SurfaceProcessor,
        SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "BrightnessOverlay";

    // Держим входные Surface достижимыми, пока их не освободят.
    private static final ThreadLocal<Map<SurfaceTexture, Surface>> sInputSurfaces =
            new ThreadLocal<Map<SurfaceTexture, Surface>>() {
                @Override
                protected Map<SurfaceTexture, Surface> initialValue() {
                    return new HashMap<>();
                }
            };

    private static final long OVERLAY_UPDATE_TIMEOUT_MILLIS = 30L;

    // --- Автояркость ---------------------------------------------------------
    // Яркость сцены измеряем прямо здесь, из кадров, которые и так проходят через эффект:
    // отдельный ImageAnalysis не нужен, а на MTK он занимал второй поток и ронял разрешение
    // записи с 1080p до 480p (регресс v1.21, исправлено в v1.24).
    /** Средняя яркость кадра, к которой стремимся при осветлении (шкала Y, 0…255). */
    private static final float AUTO_BRIGHT_TARGET_Y = 70f;
    /** Включение осветления: сглаженная яркость ниже этого порога. */
    private static final float AUTO_BRIGHT_ON_Y = 60f;
    /**
     * Выключение осветления: сглаженная яркость выше этого порога. Разрыв с
     * {@link #AUTO_BRIGHT_ON_Y} — гистерезис: не даёт усилению дёргаться у порога
     * (фары встречных машин, тени деревьев) и «качать» яркость.
     */
    private static final float AUTO_BRIGHT_OFF_Y = 88f;
    /** Максимальное цифровое усиление: выше — слишком много шума на тёмных сценах. */
    private static final float AUTO_BRIGHT_MAX_GAIN = 3.0f;
    /** Шаг усиления за одну оценку: вверх медленнее, вниз быстрее (чтобы не пересветить). */
    private static final float AUTO_BRIGHT_STEP_UP = 0.25f;
    private static final float AUTO_BRIGHT_STEP_DOWN = 0.5f;
    /**
     * Вес нового замера в сглаженной яркости (EMA). 0.4 при замере раз в секунду —
     * это реакция за ~2–3 с, без мгновенного скачка на одиночном тёмном/светлом кадре.
     */
    private static final float AUTO_BRIGHT_SMOOTHING = 0.4f;
    /** Мини-кадр, в который рендерим кадр для замера яркости (пиксели). */
    private static final int LUMA_WIDTH = 32;
    private static final int LUMA_HEIGHT = 18;
    /** Как часто пересчитывать усиление: примерно раз в секунду при ~30 к/с. */
    private static final int AUTO_BRIGHT_EVAL_FRAMES = 30;
    /** Как часто писать измеренную яркость в лог, мс. */
    private static final long AUTO_BRIGHT_LOG_INTERVAL_MS = 5000L;

    private final Handler mGlHandler;
    private final Executor mGlExecutor;
    private final GlContext mGlContext = new GlContext();
    private final GlProgramOverlay mProgramOverlay = new GlProgramOverlay();
    private final GlProgramCopy mProgramCopy = new GlProgramCopy();

    private final float[] mSurfaceTransform = new float[16];
    private final float[] mTextureTransform = new float[16];

    private Size mInputSize = null;
    private int mInputTextureId = -1;
    private int mOverlayTextureId = -1;
    private Surface mOverlaySurface;
    private SurfaceTexture mOverlayTexture;

    // Ресурсы для замера яркости: FBO с крошечной текстурой и буфер под пиксели.
    private int mLumaFboId = -1;
    private int mLumaTextureId = -1;
    private final ByteBuffer mLumaPixels =
            ByteBuffer.allocateDirect(LUMA_WIDTH * LUMA_HEIGHT * 4);

    private SurfaceOutput mOutputSurfaceOutput = null;
    private Surface mOutputSurface = null;
    private SurfaceRequest.TransformationInfo mTransformationInfo = null;
    private OverlayDrawListener mOnDrawListener;

    private volatile float mBrightness = 1.0f;
    private volatile boolean mAutoBrightEnabled = false;
    private int mFramesSinceEval = 0;
    /** Сглаженная (EMA) яркость сцены; -1 — ещё не мерили. */
    private float mSmoothedLuma = -1f;
    /** Текущее состояние гистерезиса: осветляем или уже вышли из тёмной зоны. */
    private boolean mBrightening = false;
    private volatile int mLastMeanLuma = -1;
    private long mLastLumaLogAt = 0L;
    private boolean mIsReleased = false;

    private final HandlerThread mOverlayHandlerThread;
    private final Handler mOverlayHandler;

    public BrightnessOverlayProcessor(Handler glHandler) {
        mGlHandler = glHandler;
        mGlExecutor = new HandlerExecutor(glHandler);
        mOverlayHandlerThread = new HandlerThread("dashcam-overlay-texture");
        mOverlayHandlerThread.start();
        mOverlayHandler = new Handler(mOverlayHandlerThread.getLooper());
        runOnGlThread(() -> {
            mGlContext.init();
            mProgramOverlay.init();
            mProgramCopy.init();
            mInputTextureId = createTextureId();
            configureExternalTexture(mInputTextureId);
            mOverlayTextureId = createTextureId();
            configureExternalTexture(mOverlayTextureId);
            mOverlayTexture = new SurfaceTexture(mOverlayTextureId);
            mOverlaySurface = new Surface(mOverlayTexture);
            createLumaTarget();
        });
    }

    @Override
    public void onInputSurface(SurfaceRequest surfaceRequest) {
        checkGlThread();
        if (mIsReleased) {
            surfaceRequest.willNotProvideSurface();
            return;
        }

        SurfaceTexture surfaceTexture = new SurfaceTexture(mInputTextureId);
        surfaceTexture.setDefaultBufferSize(surfaceRequest.getResolution().getWidth(),
                surfaceRequest.getResolution().getHeight());
        Surface surface = new Surface(surfaceTexture);
        sInputSurfaces.get().put(surfaceTexture, surface);
        surfaceRequest.provideSurface(surface, mGlExecutor, result -> {
            surfaceTexture.setOnFrameAvailableListener(null);
            surfaceTexture.release();
            surface.release();
            sInputSurfaces.get().remove(surfaceTexture);
        });
        surfaceTexture.setOnFrameAvailableListener(this, mGlHandler);

        mTransformationInfo = null;
        surfaceRequest.setTransformationInfoListener(mGlExecutor,
                transformationInfo -> mTransformationInfo = transformationInfo);

        configureInputSize(surfaceRequest.getResolution());
    }

    @Override
    public void onOutputSurface(SurfaceOutput surfaceOutput) {
        checkGlThread();
        if (mIsReleased) {
            surfaceOutput.close();
            return;
        }

        Surface surface = surfaceOutput.getSurface(mGlExecutor, result -> {
            surfaceOutput.close();
            if (mOutputSurfaceOutput == surfaceOutput) {
                if (mOutputSurface != null) {
                    mGlContext.unregisterSurface(mOutputSurface);
                }
                mOutputSurfaceOutput = null;
                mOutputSurface = null;
            }
        });

        if (mOutputSurface != null) {
            mGlContext.unregisterSurface(mOutputSurface);
        }
        mGlContext.registerSurface(surface);
        mOutputSurface = surface;
        mOutputSurfaceOutput = surfaceOutput;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        checkGlThread();
        if (mIsReleased) {
            return;
        }
        if (mOutputSurface == null || mOutputSurfaceOutput == null) {
            return;
        }

        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(mTextureTransform);
        mOutputSurfaceOutput.updateTransformMatrix(mSurfaceTransform, mTextureTransform);

        updateAutoBrightness();

        if (drawOverlay(surfaceTexture.getTimestamp())) {
            mProgramOverlay.draw(mInputTextureId, mOverlayTextureId, mSurfaceTransform,
                    mGlContext, mOutputSurface, surfaceTexture.getTimestamp(), mBrightness);
        }
    }

    /**
     * Включает/выключает цифровое осветление тёмной сцены.
     *
     * <p>Когда включено, процессор сам измеряет среднюю яркость каждого кадра и плавно
     * подбирает множитель. Метод безопасно вызывать из любого потока. По умолчанию выключено
     * (кадр идёт без изменений).
     */
    public void setAutoBrightEnabled(boolean enabled) {
        mAutoBrightEnabled = enabled;
    }

    /** Текущий применённый множитель яркости (1.0 — без изменений). */
    public float getBrightness() {
        return mBrightness;
    }

    /** Последняя измеренная средняя яркость кадра (0…255) или -1, если ещё не мерили. */
    public int getLastMeanLuma() {
        return mLastMeanLuma;
    }

    /**
     * Слушатель отрисовки метки. Вызывается на GL-потоке перед каждым кадром.
     * Возврат {@code false} — кадр не рисуется.
     */
    public void setOnDrawListener(OverlayDrawListener listener) {
        runOnGlThread(() -> mOnDrawListener = listener);
    }

    /** Освобождает процессор и все ресурсы. */
    public void release() {
        runOnGlThread(() -> {
            if (mIsReleased) {
                return;
            }
            if (mOutputSurfaceOutput != null) {
                mOutputSurfaceOutput.close();
                mOutputSurfaceOutput = null;
                mOutputSurface = null;
            }
            mGlContext.release();
            mProgramOverlay.release();
            mProgramCopy.release();
            if (mInputTextureId != -1) {
                android.opengl.GLES20.glDeleteTextures(1, new int[]{mInputTextureId}, 0);
                mInputTextureId = -1;
            }
            if (mOverlayTextureId != -1) {
                android.opengl.GLES20.glDeleteTextures(1, new int[]{mOverlayTextureId}, 0);
                mOverlayTextureId = -1;
            }
            if (mLumaTextureId != -1) {
                GLES20.glDeleteTextures(1, new int[]{mLumaTextureId}, 0);
                mLumaTextureId = -1;
            }
            if (mLumaFboId != -1) {
                GLES20.glDeleteFramebuffers(1, new int[]{mLumaFboId}, 0);
                mLumaFboId = -1;
            }
            if (mOverlayTexture != null) {
                mOverlayTexture.release();
                mOverlayTexture = null;
            }
            if (mOverlaySurface != null) {
                mOverlaySurface.release();
                mOverlaySurface = null;
            }
            mOverlayHandlerThread.quitSafely();
            mInputSize = null;
            mIsReleased = true;
        });
    }

    public Executor getGlExecutor() {
        return mGlExecutor;
    }

    public Handler getGlHandler() {
        return mGlHandler;
    }

    // --- Приватное ---

    private void runOnGlThread(Runnable runnable) {
        if (isGlThread()) {
            runnable.run();
        } else {
            mGlHandler.post(runnable);
        }
    }

    private void configureInputSize(Size inputSize) {
        checkGlThread();
        if (inputSize.equals(mInputSize)) {
            return;
        }
        mInputSize = inputSize;

        if (mOverlayTexture == null) {
            return;
        }
        mOverlayTexture.setDefaultBufferSize(mInputSize.getWidth(), mInputSize.getHeight());
        Canvas canvas = mOverlaySurface.lockCanvas(null);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        blockAndPostOverlay(canvas);
    }

    /** Создаёт крошечную текстуру и FBO, в которые рендерим кадр для замера яркости. */
    private void createLumaTarget() {
        checkGlThread();
        mLumaTextureId = createTextureId();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mLumaTextureId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                LUMA_WIDTH, LUMA_HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        mLumaFboId = GlUtils.createFbo();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mLumaFboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, mLumaTextureId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO для замера яркости не полон: 0x" + Integer.toHexString(status));
            mLumaFboId = -1;
        }
    }

    /**
     * Плавно подстраивает усиление яркости под текущую сцену. Вызывается на GL-потоке для
     * каждого кадра: замеряет яркость раз в {@link #AUTO_BRIGHT_EVAL_FRAMES} кадров.
     */
    private void updateAutoBrightness() {
        if (!mAutoBrightEnabled) {
            if (mBrightness != 1.0f) {
                mBrightness = 1.0f;
            }
            mLastMeanLuma = -1;
            mSmoothedLuma = -1f;
            mBrightening = false;
            mFramesSinceEval = 0;
            return;
        }
        if (++mFramesSinceEval < AUTO_BRIGHT_EVAL_FRAMES) {
            return;
        }
        mFramesSinceEval = 0;

        int luma = measureMeanLuma();
        if (luma < 0) {
            return;
        }
        mLastMeanLuma = luma;
        // Сглаживаем: одиночный тёмный/светлый кадр (тень, фары) не должен менять усиление.
        mSmoothedLuma = mSmoothedLuma < 0
                ? luma
                : mSmoothedLuma + (luma - mSmoothedLuma) * AUTO_BRIGHT_SMOOTHING;

        // Гистерезис: включаемся только в заметной темноте, выключаемся с запасом.
        if (!mBrightening && mSmoothedLuma < AUTO_BRIGHT_ON_Y) {
            mBrightening = true;
        } else if (mBrightening && mSmoothedLuma >= AUTO_BRIGHT_OFF_Y) {
            mBrightening = false;
        }

        float target = 1.0f;
        if (mBrightening) {
            float wanted = AUTO_BRIGHT_TARGET_Y / Math.max(mSmoothedLuma, 1f);
            // Только осветляем: усиление ниже 1.0 было бы затемнением кадра.
            target = Math.min(AUTO_BRIGHT_MAX_GAIN, Math.max(1.0f, wanted));
        }
        float step = target > mBrightness ? AUTO_BRIGHT_STEP_UP : AUTO_BRIGHT_STEP_DOWN;
        mBrightness = approach(mBrightness, target, step);

        long now = SystemClock.elapsedRealtime();
        if (now - mLastLumaLogAt >= AUTO_BRIGHT_LOG_INTERVAL_MS) {
            mLastLumaLogAt = now;
            Log.i(TAG, "Яркость кадра YAVG=" + luma
                    + " (сглаж. " + Math.round(mSmoothedLuma) + ") → усиление " + mBrightness
                    + (mBrightening ? " (осветление)" : ""));
        }
    }

    /**
     * Средняя яркость входного кадра (0…255). Рендерит кадр в крошечный FBO и читает пиксели:
     * 32x18, то есть ~600 пикселей за замер — дёшево даже раз в секунду. -1 — если замер невозможен.
     */
    private int measureMeanLuma() {
        checkGlThread();
        if (mLumaFboId == -1 || mInputTextureId == -1) {
            return -1;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mLumaFboId);
        GLES20.glViewport(0, 0, LUMA_WIDTH, LUMA_HEIGHT);
        mProgramCopy.draw(mInputTextureId);
        mLumaPixels.position(0);
        GLES20.glReadPixels(0, 0, LUMA_WIDTH, LUMA_HEIGHT, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, mLumaPixels);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        int count = LUMA_WIDTH * LUMA_HEIGHT;
        long sum = 0;
        for (int i = 0; i < count; i++) {
            int r = mLumaPixels.get(i * 4) & 0xFF;
            int g = mLumaPixels.get(i * 4 + 1) & 0xFF;
            int b = mLumaPixels.get(i * 4 + 2) & 0xFF;
            sum += (299 * r + 587 * g + 114 * b) / 1000;
        }
        return (int) (sum / count);
    }

    /** Плавно подводит текущее значение к целевому не более чем на {@code step} за раз. */
    private static float approach(float current, float target, float step) {
        if (current < target) {
            return Math.min(target, current + step);
        }
        if (current > target) {
            return Math.max(target, current - step);
        }
        return current;
    }

    /**
     * Просит приложение нарисовать метку, загружает результат в GPU.
     *
     * <p>Кадр рисуется, только если метод вернул true.
     */
    private boolean drawOverlay(long timestampNs) {
        checkGlThread();
        if (mTransformationInfo == null || mOnDrawListener == null || mInputSize == null) {
            return true;
        }
        SurfaceRequest.TransformationInfo info = mTransformationInfo;
        OverlayFrame frame = OverlayFrame.of(
                mOverlaySurface,
                timestampNs,
                mInputSize,
                info.getCropRect(),
                info.getRotationDegrees(),
                info.isMirroring(),
                info.getSensorToBufferTransform());

        boolean shouldRender;
        try {
            shouldRender = mOnDrawListener.onDraw(frame);
        } catch (RuntimeException e) {
            Log.e(TAG, "Ошибка отрисовки метки", e);
            shouldRender = true;
        }
        if (frame.isOverlayDirty()) {
            blockAndPostOverlay(frame.getOverlayCanvas());
        }
        return shouldRender;
    }

    private void blockAndPostOverlay(Canvas canvas) {
        checkGlThread();
        final Semaphore semaphore = new Semaphore(0);
        mOverlayTexture.setOnFrameAvailableListener(
                surfaceTexture -> semaphore.release(),
                mOverlayHandler);
        mOverlaySurface.unlockCanvasAndPost(canvas);
        try {
            boolean ok = semaphore.tryAcquire(OVERLAY_UPDATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (!ok) {
                Log.e(TAG, "Timed out waiting canvas post");
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted waiting canvas post", e);
        }
        mOverlayTexture.updateTexImage();
    }

    private void checkGlThread() {
        if (!isGlThread()) {
            throw new IllegalStateException("Must be called on GL thread");
        }
    }

    private boolean isGlThread() {
        return Thread.currentThread() == mGlHandler.getLooper().getThread();
    }

    /** Исполнитель, который выполняет задачи на заданном Handler. */
    private static final class HandlerExecutor implements Executor {
        private final Handler mHandler;

        HandlerExecutor(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void execute(Runnable command) {
            mHandler.post(command);
        }
    }
}
