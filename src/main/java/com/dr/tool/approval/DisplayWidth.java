package com.dr.tool.approval;

public final class DisplayWidth {
    private DisplayWidth() {
    }

    public static int width(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            int cp = s.codePointAt(i);
            if (Character.isSupplementaryCodePoint(cp)) {
                i++;
            }
            w += isWide(cp) ? 2 : 1;
        }
        return w;
    }

    public static String padRight(String s, int targetWidth) {
        String value = s == null ? "" : s;
        int current = width(value);
        if (current >= targetWidth) {
            return value;
        }
        return value + " ".repeat(targetWidth - current);
    }

    private static boolean isWide(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        if (block == null) {
            return false;
        }
        if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS) {
            return true;
        }
        // 大多数 emoji 在终端中按双宽显示。
        return cp >= 0x1F300 && cp <= 0x1FAFF;
    }
}
