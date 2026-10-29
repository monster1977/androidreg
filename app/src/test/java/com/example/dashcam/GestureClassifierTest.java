package com.example.dashcam;

import static org.junit.Assert.assertEquals;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Правила распознавания жестов проверяем на синтетической кисти, без камеры. */
public class GestureClassifierTest {

    private static final float WRIST_X = 0.5f;
    private static final float WRIST_Y = 0.9f;
    private static final float PIP_Y = 0.6f;
    private static final float TIP_EXTENDED_Y = 0.35f;
    private static final float TIP_FOLDED_Y = 0.8f;

    @Test
    public void victory() {
        assertEquals(GestureClassifier.VICTORY,
                GestureClassifier.classify(hand(true, true, false, false)));
    }

    @Test
    public void horns() {
        assertEquals(GestureClassifier.HORNS,
                GestureClassifier.classify(hand(true, false, false, true)));
    }

    @Test
    public void palm() {
        assertEquals(GestureClassifier.PALM,
                GestureClassifier.classify(hand(true, true, true, true)));
    }

    @Test
    public void fist() {
        assertEquals(GestureClassifier.FIST,
                GestureClassifier.classify(hand(false, false, false, false)));
    }

    @Test
    public void unknownCombination() {
        assertEquals(GestureClassifier.NONE,
                GestureClassifier.classify(hand(true, false, true, false)));
    }

    @Test
    public void noHand() {
        assertEquals(GestureClassifier.NONE, GestureClassifier.classify(null));
        assertEquals(GestureClassifier.NONE,
                GestureClassifier.classify(new ArrayList<NormalizedLandmark>()));
    }

    @Test
    public void rotationDoesNotChangeGesture() {
        List<NormalizedLandmark> victory = hand(true, true, false, false);
        assertEquals(GestureClassifier.VICTORY,
                GestureClassifier.classify(rotate(victory, 90)));
        assertEquals(GestureClassifier.VICTORY,
                GestureClassifier.classify(rotate(victory, 180)));
        assertEquals(GestureClassifier.VICTORY,
                GestureClassifier.classify(rotate(victory, 37)));

        List<NormalizedLandmark> fist = hand(false, false, false, false);
        assertEquals(GestureClassifier.FIST, GestureClassifier.classify(rotate(fist, 270)));
    }

    private static List<NormalizedLandmark> hand(boolean index, boolean middle,
                                                 boolean ring, boolean pinky) {
        List<NormalizedLandmark> hand = new ArrayList<>(21);
        for (int i = 0; i < 21; i++) {
            hand.add(NormalizedLandmark.create(WRIST_X, WRIST_Y, 0f));
        }
        setFinger(hand, 6, 8, index);
        setFinger(hand, 10, 12, middle);
        setFinger(hand, 14, 16, ring);
        setFinger(hand, 18, 20, pinky);
        return hand;
    }

    private static void setFinger(List<NormalizedLandmark> hand, int pip, int tip,
                                  boolean extended) {
        hand.set(pip, NormalizedLandmark.create(WRIST_X, PIP_Y, 0f));
        hand.set(tip, NormalizedLandmark.create(WRIST_X,
                extended ? TIP_EXTENDED_Y : TIP_FOLDED_Y, 0f));
    }

    private static List<NormalizedLandmark> rotate(List<NormalizedLandmark> hand, double degrees) {
        double radians = Math.toRadians(degrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        List<NormalizedLandmark> rotated = new ArrayList<>(hand.size());
        for (NormalizedLandmark point : hand) {
            float dx = point.x() - WRIST_X;
            float dy = point.y() - WRIST_Y;
            rotated.add(NormalizedLandmark.create(
                    WRIST_X + dx * cos - dy * sin,
                    WRIST_Y + dx * sin + dy * cos,
                    0f));
        }
        return rotated;
    }
}
