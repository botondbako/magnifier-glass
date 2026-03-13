package com.magnifierglass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class TessLangMapTest {

    /** All non-English app locales (values-XX directories). */
    private static final Set<String> APP_LOCALES = new HashSet<>(Arrays.asList(
            "bg", "ca", "cs", "da", "de", "el", "es", "et", "fi", "fr",
            "hr", "hu", "is", "it", "lt", "lv", "nb", "nl", "pl", "pt",
            "ro", "sk", "sl", "sq", "sr", "sv", "tr", "uk"
    ));

    /** Valid Tesseract ISO 639-3 codes for the languages we use. */
    private static final Set<String> VALID_TESS_CODES = new HashSet<>(Arrays.asList(
            "bul", "cat", "ces", "dan", "deu", "ell", "spa", "est", "fin", "fra",
            "hrv", "hun", "isl", "ita", "lit", "lav", "nor", "nld", "pol", "por",
            "ron", "sqi", "slk", "slv", "srp", "swe", "tur", "ukr"
    ));

    @Test
    public void everyAppLocale_hasTessMapping() {
        for (String locale : APP_LOCALES) {
            assertNotNull("Missing TESS_LANG_MAP entry for locale: " + locale,
                    MagnifierGlassActivity.TESS_LANG_MAP.get(locale));
        }
    }

    @Test
    public void allMappedCodes_areValidIso639_3() {
        for (java.util.Map.Entry<String, String> entry : MagnifierGlassActivity.TESS_LANG_MAP.entrySet()) {
            assertTrue("Invalid Tesseract code '" + entry.getValue() + "' for locale '" + entry.getKey() + "'",
                    VALID_TESS_CODES.contains(entry.getValue()));
        }
    }

    @Test
    public void english_notInMap() {
        // English is bundled, not downloaded — should not be in the map
        assertFalse("English should not be in TESS_LANG_MAP",
                MagnifierGlassActivity.TESS_LANG_MAP.containsKey("en"));
    }

    @Test
    public void mapSize_matchesAppLocales() {
        assertEquals("Map should have exactly one entry per non-English locale",
                APP_LOCALES.size(), MagnifierGlassActivity.TESS_LANG_MAP.size());
    }

    @Test
    public void noDuplicateTessCodes() {
        Set<String> seen = new HashSet<>();
        for (String code : MagnifierGlassActivity.TESS_LANG_MAP.values()) {
            assertTrue("Duplicate Tesseract code: " + code, seen.add(code));
        }
    }
}
