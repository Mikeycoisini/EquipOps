package com.support;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class A1BootstrapTest {
    @Test void productionSchemaIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> A1Bootstrap.validateTarget("equipops_v2", "equipops_v2", "x".repeat(24)));
    }
    @Test void unconfirmedSchemaIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> A1Bootstrap.validateTarget("equipops_a1_ci", "equipops_a1_other", "x".repeat(24)));
    }
    @Test void absentOrShortPasswordIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> A1Bootstrap.validateTarget("equipops_a1_ci", "equipops_a1_ci", null));
        assertThrows(IllegalArgumentException.class,
                () -> A1Bootstrap.validateTarget("equipops_a1_ci", "equipops_a1_ci", "short"));
    }
    @Test void explicitlyConfirmedIsolatedTargetIsAccepted() {
        assertDoesNotThrow(() -> A1Bootstrap.validateTarget(
                "equipops_a1_ci", "equipops_a1_ci", "x".repeat(24)));
    }
}
