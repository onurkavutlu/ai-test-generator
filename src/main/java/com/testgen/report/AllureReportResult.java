package com.testgen.report;

import lombok.Getter;

@Getter
public class AllureReportResult {

    private final boolean success;
    private final String reportPath;   // disk path
    private final String reportUrl;    // HTTP URL
    private final String errorMessage;

    private AllureReportResult(boolean success, String reportPath,
                                String reportUrl, String errorMessage) {
        this.success      = success;
        this.reportPath   = reportPath;
        this.reportUrl    = reportUrl;
        this.errorMessage = errorMessage;
    }

    public static AllureReportResult success(String reportPath, String reportUrl) {
        return new AllureReportResult(true, reportPath, reportUrl, null);
    }

    public static AllureReportResult failed(String errorMessage) {
        return new AllureReportResult(false, null, null, errorMessage);
    }
}
