package com.example.dashcam;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Разбор голосовых команд. Само распознавание проверить без микрофона нельзя,
 * а правила «фраза → действие» — можно.
 */
public class VoiceCommandsTest {

    @Test
    public void startRecording() {
        assertEquals(VoiceCommands.START_RECORDING, VoiceCommands.match("Запись"));
        assertEquals(VoiceCommands.START_RECORDING, VoiceCommands.match("начать запись"));
        assertEquals(VoiceCommands.START_RECORDING, VoiceCommands.match("включи запись"));
        assertEquals(VoiceCommands.START_RECORDING, VoiceCommands.match("начни запись"));
        assertEquals(VoiceCommands.START_RECORDING, VoiceCommands.match("старт"));
        assertEquals(VoiceCommands.START_RECORDING, VoiceCommands.match("поехали"));
    }

    @Test
    public void openGallery() {
        assertEquals(VoiceCommands.OPEN_GALLERY, VoiceCommands.match("Галерея"));
        assertEquals(VoiceCommands.OPEN_GALLERY, VoiceCommands.match("открой галерею"));
        assertEquals(VoiceCommands.OPEN_GALLERY, VoiceCommands.match("покажи записи"));
        assertEquals(VoiceCommands.OPEN_GALLERY, VoiceCommands.match("список записей"));
    }

    @Test
    public void openSettings() {
        assertEquals(VoiceCommands.OPEN_SETTINGS, VoiceCommands.match("Настройки"));
        assertEquals(VoiceCommands.OPEN_SETTINGS, VoiceCommands.match("открой настройки"));
    }

    @Test
    public void stopInIdleModeIsNotStart() {
        assertEquals(VoiceCommands.ALREADY_STOPPED, VoiceCommands.match("стоп"));
        assertEquals(VoiceCommands.ALREADY_STOPPED, VoiceCommands.match("останови запись"));
        assertEquals(VoiceCommands.ALREADY_STOPPED, VoiceCommands.match("выключи запись"));
    }

    @Test
    public void unknownPhrases() {
        assertEquals(VoiceCommands.NONE, VoiceCommands.match("привет"));
        assertEquals(VoiceCommands.NONE, VoiceCommands.match("как дела"));
        assertEquals(VoiceCommands.NONE, VoiceCommands.match(""));
        assertEquals(VoiceCommands.NONE, VoiceCommands.match("   "));
        assertEquals(VoiceCommands.NONE, VoiceCommands.match(null));
    }

    @Test
    public void caseAndYoAreNormalized() {
        assertEquals(VoiceCommands.START_RECORDING, VoiceCommands.match("ЗАПИСЬ"));
        assertEquals(VoiceCommands.START_RECORDING, VoiceCommands.match("Поехали!"));
        assertEquals(VoiceCommands.OPEN_SETTINGS, VoiceCommands.match("НАСТРОЙКИ"));
    }
}
