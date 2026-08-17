package com.testgen.orchestration;

import com.testgen.model.TestFramework;

/** Planlanan framework adapter'ı registry'de yoksa kullanılan açık hata. */
public class FrameworkUnavailableException extends RuntimeException {
    public FrameworkUnavailableException(TestFramework framework) {
        super("Framework generator kullanılamıyor: " + framework);
    }
}
