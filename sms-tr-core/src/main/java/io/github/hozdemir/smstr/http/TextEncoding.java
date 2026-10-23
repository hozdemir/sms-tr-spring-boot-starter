package io.github.hozdemir.smstr.http;

/** The narrowest SMS character set that carries a text intact; fewer bytes per character means cheaper SMS. */
public enum TextEncoding {

    /** GSM 03.38 default alphabet. */
    GSM,
    /** GSM with the Turkish national language shift table (ş, ğ, ı, İ ...). */
    TURKISH,
    /** UCS-2: anything else, including emoji. */
    UNICODE;

    private static final String GSM_CHARS = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà^{}\\[~]|€";
    private static final String TURKISH_CHARS = "şŞğĞıİçÇöÖüÜ";
    private static final String TO_ASCII_FROM = "şŞğĞıİçÇöÖüÜ";
    private static final String TO_ASCII_TO = "sSgGiIcCoOuU";

    public static TextEncoding detect(String text) {
        TextEncoding needed = GSM;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (GSM_CHARS.indexOf(c) >= 0) {
                continue;
            }
            if (TURKISH_CHARS.indexOf(c) >= 0) {
                needed = TURKISH;
                continue;
            }
            return UNICODE;
        }
        return needed;
    }

    /** Replace Turkish letters with their ASCII look-alikes, for channels that reject them. */
    public static String transliterate(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int index = TO_ASCII_FROM.indexOf(c);
            result.append(index >= 0 ? TO_ASCII_TO.charAt(index) : c);
        }
        return result.toString();
    }
}
