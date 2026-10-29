package com.example.dashcam;

import java.util.Locale;

/**
 * Разбор распознанной фразы в команду.
 *
 * <p>Вынесено из {@link MainActivity}, чтобы правила можно было покрыть юнит-тестами:
 * распознавание речи на телефоне без микрофона не проверить, а сопоставление фраз — можно.</p>
 */
final class VoiceCommands {

    static final int NONE = 0;
    static final int START_RECORDING = 1;
    static final int OPEN_GALLERY = 2;
    static final int OPEN_SETTINGS = 3;
    /** «Стоп» в дежурном режиме: запись и так не идёт. */
    static final int ALREADY_STOPPED = 4;

    private VoiceCommands() {
    }

    static int match(String phrase) {
        if (phrase == null) {
            return NONE;
        }
        String text = phrase.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
        if (text.isEmpty()) {
            return NONE;
        }
        // «Останови запись» содержит «запис», но запускать запись тут нельзя.
        if (text.contains("стоп") || text.contains("останов") || text.contains("выключ")) {
            return ALREADY_STOPPED;
        }
        if (text.contains("настройк")) {
            return OPEN_SETTINGS;
        }
        // Галерею просим отдельным словом: «записи» слишком похоже на «запись».
        if (text.contains("галере") || text.contains("покажи запис")
                || text.contains("список запис")) {
            return OPEN_GALLERY;
        }
        if (text.contains("запис") || text.contains("старт")
                || text.contains("поехали") || text.contains("начни")) {
            return START_RECORDING;
        }
        return NONE;
    }
}
