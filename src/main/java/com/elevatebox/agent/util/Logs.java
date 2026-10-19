package com.elevatebox.agent.util;

/** Helpers for keeping personal data out of application logs. */
public final class Logs {

    private Logs() {}

    /** Masks all but the last four characters, e.g. {@code +919876543210} becomes {@code *********3210}. */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "****";
        }
        return "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }
}
