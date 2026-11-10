package com.mfb.lending.module.customer.application;

public final class BankDirectory {

    private BankDirectory() {
    }

    public static String name(String bankCode) {
        return switch (bankCode) {
            case "044" -> "Access Bank";
            case "058" -> "Guaranty Trust Bank";
            case "011" -> "First Bank";
            case "033" -> "United Bank for Africa";
            case "057" -> "Zenith Bank";
            case "070" -> "Fidelity Bank";
            case "214" -> "First City Monument Bank";
            case "032" -> "Union Bank";
            case "035" -> "Wema Bank";
            case "221" -> "Stanbic IBTC Bank";
            case "050" -> "Ecobank";
            case "030" -> "Heritage Bank";
            case "082" -> "Keystone Bank";
            case "076" -> "Polaris Bank";
            case "101" -> "Providus Bank";
            case "232" -> "Sterling Bank";
            default -> "Bank " + bankCode;
        };
    }
}
