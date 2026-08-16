package com.testgen.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ObservationHttpClientTest {

    @Test
    void secureAndInsecureClientsCanBeBuilt() {
        var secure = ObservationService.buildHttpClient(false);
        var insecure = ObservationService.buildHttpClient(true);

        assertNotNull(secure);
        assertNotNull(insecure);
        assertNotSame(secure.sslContext(), insecure.sslContext());
    }
}
