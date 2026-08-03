package com.testgen.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class HarFileParser {

    private final ObjectMapper objectMapper;

    public List<ParsedRequestDto> parse(String jsonPayload) {
        List<ParsedRequestDto> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(jsonPayload);
            if (root.has("log") && root.get("log").has("entries")) {
                JsonNode entries = root.get("log").get("entries");
                for (JsonNode entry : entries) {
                    if (entry.has("request") && entry.has("response")) {
                        JsonNode request = entry.get("request");
                        JsonNode response = entry.get("response");
                        
                        // Sadece application/json dönen veya API isteği olabilecekleri alalım
                        boolean isApiRequest = false;
                        if (response.has("content") && response.get("content").has("mimeType")) {
                            String mimeType = response.get("content").get("mimeType").asText();
                            if (mimeType.contains("json") || mimeType.contains("xml")) {
                                isApiRequest = true;
                            }
                        }
                        
                        if (isApiRequest) {
                            String method = request.has("method") ? request.get("method").asText() : "GET";
                            String url = request.has("url") ? request.get("url").asText() : "Unknown URL";
                            String name = method + " " + extractPath(url);
                            
                            // Hem request hem response'u LLM'e göndermek için birleştiriyoruz
                            String details = "Request:\n" + request.toPrettyString() + "\n\nResponse:\n" + response.toPrettyString();
                            
                            results.add(new ParsedRequestDto(name, method, url, details));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("HAR file parse hatası", e);
        }
        return results;
    }
    
    private String extractPath(String url) {
        try {
            java.net.URL parsedUrl = new java.net.URL(url);
            return parsedUrl.getPath();
        } catch (Exception e) {
            return url;
        }
    }
}
