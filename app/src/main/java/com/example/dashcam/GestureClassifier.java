package com.example.dashcam;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

/**
 * Жест по 21 точке кисти MediaPipe Hand Landmarker.
 *
 * <p>Пальцы считаются разогнутыми по расстоянию от запястья: у выпрямленного пальца
 * кончик дальше от запястья, чем средний сустав. Такая проверка не зависит от поворота
 * кисти на экране (сравниваются расстояния, а не координаты по осям).</p>
 *
 * <p>Большой палец в классификации не участвует: он сильнее всего «шумит» при
 * частичном перекрытии ладони, а четыре жеста ниже надёжно различаются и без него.</p>
 */
public final class GestureClassifier {

    public static final String NONE = "NONE";
    /** «V» — указательный и средний. */
    public static final String VICTORY = "VICTORY";
    /** «Коза» — указательный и мизинец. */
    public static final String HORNS = "HORNS";
    /** Открытая ладонь — все четыре пальца. */
    public static final String PALM = "PALM";
    /** Кулак — ни одного разогнутого пальца. */
    public static final String FIST = "FIST";

    private static final int WRIST = 0;
    private static final int INDEX_TIP = 8;
    private static final int INDEX_PIP = 6;
    private static final int MIDDLE_TIP = 12;
    private static final int MIDDLE_PIP = 10;
    private static final int RING_TIP = 16;
    private static final int RING_PIP = 14;
    private static final int PINKY_TIP = 20;
    private static final int PINKY_PIP = 18;

    /** Запас, чтобы дрожание сустава не переключало палец туда-обратно. */
    private static final float EXTENDED_RATIO = 1.08f;

    private GestureClassifier() {
    }

    /** @return одна из констант класса или {@link #NONE}, если поза не распознана. */
    public static String classify(List<NormalizedLandmark> hand) {
        if (hand == null || hand.size() < 21) {
            return NONE;
        }
        boolean index = extended(hand, INDEX_TIP, INDEX_PIP);
        boolean middle = extended(hand, MIDDLE_TIP, MIDDLE_PIP);
        boolean ring = extended(hand, RING_TIP, RING_PIP);
        boolean pinky = extended(hand, PINKY_TIP, PINKY_PIP);

        if (index && middle && !ring && !pinky) {
            return VICTORY;
        }
        if (index && !middle && !ring && pinky) {
            return HORNS;
        }
        if (index && middle && ring && pinky) {
            return PALM;
        }
        if (!index && !middle && !ring && !pinky) {
            return FIST;
        }
        return NONE;
    }

    private static boolean extended(List<NormalizedLandmark> hand, int tip, int pip) {
        NormalizedLandmark wrist = hand.get(WRIST);
        return distance(hand.get(tip), wrist) > distance(hand.get(pip), wrist) * EXTENDED_RATIO;
    }

    private static float distance(NormalizedLandmark a, NormalizedLandmark b) {
        float dx = a.x() - b.x();
        float dy = a.y() - b.y();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
