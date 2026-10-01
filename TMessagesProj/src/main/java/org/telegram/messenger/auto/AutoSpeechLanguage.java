package org.telegram.messenger.auto;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks a voice language per spoken fragment. Fixed wording is English; names, addresses and the
 * note use the message language (ML Kit tag) when their alphabet matches it, otherwise the usual
 * language of their alphabet, so a Cyrillic name is never spelled out by an English voice.
 */
final class AutoSpeechLanguage {
    enum Script { NONE, LATIN, CYRILLIC, GREEK, HEBREW, ARABIC, HAN, KANA, HANGUL, THAI, DEVANAGARI, ARMENIAN, GEORGIAN }

    static final class Utterance {
        final String text;
        final Locale locale;

        Utterance(String text, Locale locale) {
            this.text = text;
            this.locale = locale;
        }
    }

    private AutoSpeechLanguage() {
    }

    /** Text worth detecting: only the message-derived fragments, not the English wording. */
    static String contentText(List<List<AutoPlaceSummaryBuilder.Part>> sentences) {
        StringBuilder text = new StringBuilder();
        for (List<AutoPlaceSummaryBuilder.Part> sentence : sentences) {
            for (AutoPlaceSummaryBuilder.Part part : sentence) {
                if (!part.content) continue;
                if (text.length() > 0) text.append(". ");
                text.append(part.text);
            }
        }
        return text.toString();
    }

    /** Adjacent fragments with the same voice are merged; each sentence ends with a full stop. */
    static List<Utterance> resolve(List<List<AutoPlaceSummaryBuilder.Part>> sentences,
                                   @Nullable String detected, @NonNull Locale device) {
        ArrayList<Utterance> result = new ArrayList<>();
        for (List<AutoPlaceSummaryBuilder.Part> sentence : sentences) {
            for (int i = 0; i < sentence.size(); i++) {
                AutoPlaceSummaryBuilder.Part part = sentence.get(i);
                String text = part.text.trim();
                if (text.isEmpty()) continue;
                if (i == sentence.size() - 1 && !endsWithPunctuation(text)) text += ".";
                Locale locale = part.content ? contentLocale(text, detected, device) : Locale.ENGLISH;
                Utterance last = result.isEmpty() ? null : result.get(result.size() - 1);
                if (last != null && sameVoice(last.locale, locale)) {
                    String separator = text.startsWith(",") ? "" : " ";
                    result.set(result.size() - 1, new Utterance(last.text + separator + text, last.locale));
                } else {
                    result.add(new Utterance(text, locale));
                }
            }
        }
        return result;
    }

    static Locale contentLocale(String text, @Nullable String detected, @NonNull Locale device) {
        Script script = dominantScript(text);
        String language = normalize(detected);
        Script detectedScript = language == null ? null : scriptOf(language);
        if (script == Script.NONE) return Locale.ENGLISH;
        if (script == Script.LATIN) {
            if (detectedScript == Script.LATIN) return new Locale(language);
            return TextUtils.isEmpty(device.getLanguage()) ? Locale.ENGLISH : new Locale(device.getLanguage());
        }
        if (detectedScript == script || script == Script.HAN && "ja".equals(language)) return new Locale(language);
        return new Locale(defaultLanguage(script));
    }

    /** ML Kit returns "und" when unsure and "xx-Latn" for romanized text; both mean unknown. */
    @Nullable
    static String normalize(@Nullable String tag) {
        if (TextUtils.isEmpty(tag) || "und".equals(tag) || tag.contains("-Latn")) return null;
        int dash = tag.indexOf('-');
        return (dash > 0 ? tag.substring(0, dash) : tag).toLowerCase(Locale.ROOT);
    }

    static Script dominantScript(String text) {
        int[] counts = new int[Script.values().length];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) continue;
            counts[scriptOf(c).ordinal()]++;
        }
        Script best = Script.NONE;
        for (Script script : Script.values()) {
            if (script != Script.NONE && counts[script.ordinal()] > counts[best.ordinal()]) best = script;
        }
        return best;
    }

    private static Script scriptOf(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        if (block == null) return Script.NONE;
        if (block == Character.UnicodeBlock.CYRILLIC || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY) return Script.CYRILLIC;
        if (block == Character.UnicodeBlock.GREEK || block == Character.UnicodeBlock.GREEK_EXTENDED) return Script.GREEK;
        if (block == Character.UnicodeBlock.HEBREW) return Script.HEBREW;
        if (block == Character.UnicodeBlock.ARABIC || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B) return Script.ARABIC;
        if (block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA) return Script.KANA;
        if (block == Character.UnicodeBlock.HANGUL_SYLLABLES || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) return Script.HANGUL;
        if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) return Script.HAN;
        if (block == Character.UnicodeBlock.THAI) return Script.THAI;
        if (block == Character.UnicodeBlock.DEVANAGARI) return Script.DEVANAGARI;
        if (block == Character.UnicodeBlock.ARMENIAN) return Script.ARMENIAN;
        if (block == Character.UnicodeBlock.GEORGIAN) return Script.GEORGIAN;
        return Script.LATIN;
    }

    private static Script scriptOf(String language) {
        switch (language) {
            case "ru": case "uk": case "be": case "bg": case "sr": case "mk": case "kk": case "ky": case "mn": case "tg":
                return Script.CYRILLIC;
            case "el": return Script.GREEK;
            case "he": case "iw": case "yi": return Script.HEBREW;
            case "ar": case "fa": case "ur": case "ps": return Script.ARABIC;
            case "zh": return Script.HAN;
            case "ja": return Script.KANA;
            case "ko": return Script.HANGUL;
            case "th": return Script.THAI;
            case "hi": case "mr": case "ne": return Script.DEVANAGARI;
            case "hy": return Script.ARMENIAN;
            case "ka": return Script.GEORGIAN;
            default: return Script.LATIN;
        }
    }

    private static String defaultLanguage(Script script) {
        switch (script) {
            case CYRILLIC: return "ru";
            case GREEK: return "el";
            case HEBREW: return "he";
            case ARABIC: return "ar";
            case HAN: return "zh";
            case KANA: return "ja";
            case HANGUL: return "ko";
            case THAI: return "th";
            case DEVANAGARI: return "hi";
            case ARMENIAN: return "hy";
            case GEORGIAN: return "ka";
            default: return "en";
        }
    }

    private static boolean sameVoice(Locale a, Locale b) {
        return a.getLanguage().equals(b.getLanguage());
    }

    private static boolean endsWithPunctuation(String text) {
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == '…';
    }
}
