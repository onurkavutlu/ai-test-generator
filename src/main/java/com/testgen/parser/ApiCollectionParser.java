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
public class ApiCollectionParser {

    private final ObjectMapper objectMapper;

    public List<ParsedRequestDto> parse(String jsonPayload) {
        List<ParsedRequestDto> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(jsonPayload);
            extractItems(root, results);
        } catch (Exception e) {
            log.error("API collection parse hatası", e);
        }
        return results;
    }

    private void extractItems(JsonNode node, List<ParsedRequestDto> results) {
        if (node.has("item") && node.get("item").isArray()) {
            for (JsonNode itemNode : node.get("item")) {
                extractItems(itemNode, results);
            }
        } else if (node.has("request")) {
            // Bu bir yaprak düğümdür (bir endpoint isteği)
            String name = node.has("name") ? node.get("name").asText() : "Unknown Request";
            JsonNode requestNode = node.get("request");
            
            String method = requestNode.has("method") ? requestNode.get("method").asText() : "GET";
            String url = extractUrl(requestNode);
            
            // Ham JSON yerine kompakt özet: Postman meta verisi prompt'a taşınmaz
            String details = RequestDetailFormatter.fromPostman(requestNode, method, url);
            
            results.add(new ParsedRequestDto(name, method, url, details,
                    RequestDetailFormatter.postmanHeaders(requestNode),
                    RequestDetailFormatter.postmanBody(requestNode)));
        }
    }

    private String extractUrl(JsonNode requestNode) {
        if (!requestNode.has("url")) return "";
        JsonNode urlNode = requestNode.get("url");
        if (urlNode.isTextual()) {
            return urlNode.asText();
        } else if (urlNode.has("raw")) {
            return urlNode.get("raw").asText();
        }
        return "";
    }
}
