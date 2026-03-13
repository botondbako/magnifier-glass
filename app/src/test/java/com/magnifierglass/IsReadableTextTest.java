package com.magnifierglass;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IsReadableTextTest {

    @Test
    public void normalText() {
        assertTrue(MagnifierGlassActivity.isReadableText("Hello World"));
    }

    @Test
    public void twoCharWord_rejectedByMinLength() {
        // "OK" is only 2 chars — below the minimum length threshold
        assertFalse(MagnifierGlassActivity.isReadableText("OK"));
    }

    @Test
    public void threeCharWord() {
        assertTrue(MagnifierGlassActivity.isReadableText("Yes"));
    }

    @Test
    public void tooShort() {
        assertFalse(MagnifierGlassActivity.isReadableText("ab"));
    }

    @Test
    public void empty() {
        assertFalse(MagnifierGlassActivity.isReadableText(""));
    }

    @Test
    public void onlySymbols() {
        assertFalse(MagnifierGlassActivity.isReadableText("!@#$%^&*()"));
    }

    @Test
    public void mostlyGarbage() {
        // Less than half readable
        assertFalse(MagnifierGlassActivity.isReadableText("##|}{a"));
    }

    @Test
    public void noConsecutiveLetters() {
        // All single letters separated by symbols — no word with 2+ letters
        assertFalse(MagnifierGlassActivity.isReadableText("a-b-c-d"));
    }

    @Test
    public void digitsAndLetters() {
        assertTrue(MagnifierGlassActivity.isReadableText("Room 42"));
    }

    @Test
    public void unicodeLetters() {
        assertTrue(MagnifierGlassActivity.isReadableText("Ölüm"));
    }

    @Test
    public void emojiOnly() {
        assertFalse(MagnifierGlassActivity.isReadableText("\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02"));
    }

    @Test
    public void threeCharsMinimal() {
        // Exactly 3 chars, 2 consecutive letters → readable
        assertTrue(MagnifierGlassActivity.isReadableText("ab "));
    }

    @Test
    public void cjkText() {
        assertTrue(MagnifierGlassActivity.isReadableText("日本語テスト"));
    }

    @Test
    public void tabsAndNewlines() {
        assertTrue(MagnifierGlassActivity.isReadableText("Hello\tWorld\n"));
    }

    @Test
    public void longGarbage() {
        // 50 symbols, no letters
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append('#');
        assertFalse(MagnifierGlassActivity.isReadableText(sb.toString()));
    }

    @Test
    public void mixedScripts() {
        assertTrue(MagnifierGlassActivity.isReadableText("Привет world"));
    }

    @Test
    public void singleLetterPadded() {
        // Only one letter, no 2+ consecutive → not readable
        assertFalse(MagnifierGlassActivity.isReadableText("  a  "));
    }

    @Test
    public void numbersOnly() {
        // Digits count as readable chars but no 2+ consecutive letters
        assertFalse(MagnifierGlassActivity.isReadableText("123 456"));
    }

    @Test
    public void accentedText() {
        assertTrue(MagnifierGlassActivity.isReadableText("café résumé"));
    }
}
