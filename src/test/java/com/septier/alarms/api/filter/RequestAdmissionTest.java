package com.septier.alarms.api.filter;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequestAdmissionTest {
    @Test void concurrentRequestsAreBoundedAndPermitsAreReturned() {
        var admission = new RequestAdmission(1, 30, 120, 2);
        assertThat(admission.enter("client-a")).isTrue();
        assertThat(admission.enter("client-b")).isFalse();
        admission.exit();
        assertThat(admission.enter("client-b")).isTrue(); admission.exit();
    }
    @Test void clientMapAndBurstAreBounded() {
        var admission = new RequestAdmission(10, 1, 1, 1);
        assertThat(admission.enter("a")).isTrue(); admission.exit();
        assertThat(admission.enter("a")).isFalse();
        assertThat(admission.enter("b")).isFalse();
    }
}
