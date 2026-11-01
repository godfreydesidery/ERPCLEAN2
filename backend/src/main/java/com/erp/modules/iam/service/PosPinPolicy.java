package com.erp.modules.iam.service;

/**
 * A waiter's POS PIN: exactly 4 digits, and not one anyone would guess first — the same digit
 * four times, or a straight run up or down (1234, 4321, 0123…).
 */
public final class PosPinPolicy {

    private PosPinPolicy() {
    }

    /** Throws a user-safe {@link IllegalArgumentException} when {@code pin} is not acceptable. */
    public static void validate(String pin) {
        if (pin == null || !pin.matches("[0-9]{4}")) {
            throw new IllegalArgumentException("The PIN must be exactly 4 digits.");
        }
        int up = 0;
        int down = 0;
        boolean same = true;
        for (int i = 1; i < 4; i++) {
            int step = pin.charAt(i) - pin.charAt(i - 1);
            up += step == 1 ? 1 : 0;
            down += step == -1 ? 1 : 0;
            same &= step == 0;
        }
        if (same || up == 3 || down == 3) {
            throw new IllegalArgumentException("That PIN is too easy to guess. Choose another.");
        }
    }
}
