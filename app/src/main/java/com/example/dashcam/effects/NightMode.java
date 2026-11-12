package com.example.dashcam.effects;

/**
 * Профиль ночного режима — насколько агрессивно цифровое осветление вытягивает тёмный кадр.
 *
 * <p>Пороги и усиление подобраны так, что уровень {@link #NORMAL} в точности повторяет
 * поведение версий 1.44–1.47: включение при сглаженной яркости ниже 60, выключение выше 88,
 * цель 70 и максимальное усиление 3×. Более мягкие уровни трогают только по-настоящему
 * тёмные сцены и дают меньше шума, более агрессивные начинают осветлять раньше и сильнее.</p>
 *
 * <p>Класс без зависимостей от Android — профили проверяются юнит-тестами.</p>
 */
public final class NightMode {

    /** Мягкий: осветляем только в реальной темноте, минимальный шум. */
    public static final int SOFT = 0;
    /** Средний: прежнее поведение автояркости (по умолчанию). */
    public static final int NORMAL = 1;
    /** Сильный: светлее вечернее видео, шума заметно больше. */
    public static final int STRONG = 2;
    /** Максимальный: вытягивает даже сумерки, максимальный шум. */
    public static final int MAXIMUM = 3;

    /** Сколько ступеней предлагается в настройках. */
    public static final int COUNT = 4;
    /** Ступень по умолчанию — совпадает с автояркостью 1.46. */
    public static final int DEFAULT = NORMAL;

    /**
     * Все профили, индекс = ступень. Порядок уровней — от мягкого к максимальному;
     * тест проверяет, что усиление и пороги не убывают.
     */
    private static final NightMode[] PROFILES = {
            new NightMode(SOFT, 55f, 45f, 68f, 2.0f, 0.15f, 0.30f),
            new NightMode(NORMAL, 70f, 60f, 88f, 3.0f, 0.25f, 0.50f),
            new NightMode(STRONG, 85f, 72f, 105f, 4.5f, 0.30f, 0.60f),
            new NightMode(MAXIMUM, 100f, 88f, 125f, 6.0f, 0.35f, 0.70f),
    };

    /** Ступень (0…3). */
    public final int level;
    /** Средняя яркость кадра (0…255), к которой стремимся при осветлении. */
    public final float targetY;
    /** Включение осветления: сглаженная яркость ниже этого порога. */
    public final float onY;
    /** Выключение осветления: сглаженная яркость выше этого порога (гистерезис). */
    public final float offY;
    /** Максимальное цифровое усиление: выше — слишком много шума. */
    public final float maxGain;
    /** Шаг усиления вверх за одну оценку. */
    public final float stepUp;
    /** Шаг усиления вниз за одну оценку: быстрее, чтобы не пересветить. */
    public final float stepDown;

    private NightMode(int level, float targetY, float onY, float offY, float maxGain,
            float stepUp, float stepDown) {
        this.level = level;
        this.targetY = targetY;
        this.onY = onY;
        this.offY = offY;
        this.maxGain = maxGain;
        this.stepUp = stepUp;
        this.stepDown = stepDown;
    }

    /** Профиль для ступени; значения вне диапазона прижимаются к ближайшему. */
    public static NightMode of(int level) {
        return PROFILES[clamp(level)];
    }

    /** Прижимает ступень к допустимому диапазону 0…{@link #COUNT}-1. */
    public static int clamp(int level) {
        return Math.max(0, Math.min(COUNT - 1, level));
    }
}
