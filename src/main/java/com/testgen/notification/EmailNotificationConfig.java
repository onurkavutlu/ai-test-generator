package com.testgen.notification;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "notification.email")
public class EmailNotificationConfig {
    private boolean enabled = true;
    private String from;
    private String fromName;
    private String defaultRecipients;
    private String subjectPrefix;
    private String allureReportUrl;
    private String appBaseUrl = "http://localhost:8080";
    private boolean attachReport = true;
}
