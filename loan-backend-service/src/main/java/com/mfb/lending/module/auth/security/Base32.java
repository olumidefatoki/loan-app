package com.mfb.lending.module.auth.security;

public final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder encoded = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                encoded.append(ALPHABET[(buffer >> bits) & 0x1f]);
            }
        }
        if (bits > 0) {
            encoded.append(ALPHABET[(buffer << (5 - bits)) & 0x1f]);
        }
        return encoded.toString();
    }

    public static byte[] decode(String value) {
        String normalized = value.trim().replace("=", "").replace(" ", "").toUpperCase();
        int buffer = 0;
        int bits = 0;
        byte[] output = new byte[normalized.length() * 5 / 8];
        int index = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int digit = alphabetIndex(normalized.charAt(i));
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                output[index++] = (byte) ((buffer >> bits) & 0xff);
            }
        }
        if (index == output.length) {
            return output;
        }
        byte[] trimmed = new byte[index];
        System.arraycopy(output, 0, trimmed, 0, index);
        return trimmed;
    }

    private static int alphabetIndex(char character) {
        if (character >= 'A' && character <= 'Z') {
            return character - 'A';
        }
        if (character >= '2' && character <= '7') {
            return character - '2' + 26;
        }
        throw new IllegalArgumentException("Invalid Base32 character");
    }
}
