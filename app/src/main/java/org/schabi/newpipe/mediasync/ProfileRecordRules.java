package org.schabi.newpipe.mediasync;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Portable profile primitives, independent of Android and JSON coercion. */
public final class ProfileRecordRules {
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern CHANNEL = Pattern.compile("UC[A-Za-z0-9_-]{22}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    private ProfileRecordRules() { }

    public static boolean validName(final String name) {
        if (name == null || name.getBytes(StandardCharsets.UTF_8).length > 160) {
            return false;
        }
        boolean visible = false;
        for (int i = 0; i < name.length(); i++) {
            final char character = name.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (i + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(i + 1))) {
                    return false;
                }
                i++;
                visible = true;
                continue;
            }
            if (Character.isLowSurrogate(character)) {
                return false;
            }
            if (character < 32 || character == 127) {
                return false;
            }
            // Match ECMAScript trim() used by the paired desktop validator, including BOM.
            visible |= !isPortableWhitespace(character);
        }
        return visible;
    }

    private static boolean isPortableWhitespace(final char character) {
        return character == 0x20 || character == 0xA0 || character == 0x1680
                || (character >= 0x2000 && character <= 0x200A)
                || character == 0x2028 || character == 0x2029 || character == 0x202F
                || character == 0x205F || character == 0x3000 || character == 0xFEFF;
    }

    public static boolean validColor(final String color) {
        return color != null && COLOR.matcher(color).matches();
    }

    public static boolean validMembership(final String id, final String profileId,
                                          final String channelId) {
        return profileId != null && channelId != null && UUID.matcher(profileId).matches()
                && CHANNEL.matcher(channelId).matches()
                && (profileId + ":" + channelId).equals(id);
    }
}
