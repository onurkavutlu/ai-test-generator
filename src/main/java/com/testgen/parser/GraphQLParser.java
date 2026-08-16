package com.testgen.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GraphQLParser {

    private final ObjectMapper objectMapper;

    public List<ParsedRequestDto> parse(String jsonPayload) {
        String stripped = jsonPayload == null ? "" : jsonPayload.strip();
        if (!stripped.startsWith("{")) {
            return rawQuery(jsonPayload);
        }

        try {
            JsonNode root = objectMapper.readTree(jsonPayload);
            
            String query = root.has("query") ? root.get("query").asText() : "";
            String variables = root.has("variables") ? root.get("variables").toPrettyString() : "{}";
            String operationName = root.has("operationName") && !root.get("operationName").isNull() 
                    ? root.get("operationName").asText() 
                    : "GraphQL_Operation";
            
            if (query.isBlank()) {
                // Eğer query yoksa, belki kullanıcı direkt query'i düz metin vermiştir
                query = jsonPayload;
                operationName = "Raw_GraphQL_Query";
            }
            
            String details = String.format("Query:\n%s\n\nVariables:\n%s", query, variables);
            
            // Endpoint payload içinde yoktur; çağıran katman açık hedef sağlamalıdır.
            return Collections.singletonList(new ParsedRequestDto(
                    operationName, "POST", null, details, Map.of(), jsonPayload));
        } catch (Exception e) {
            log.warn("GraphQL JSON parse edilemedi; ham payload korunuyor: {}", e.getMessage());
            return rawQuery(jsonPayload);
        }
    }

    private List<ParsedRequestDto> rawQuery(String payload) {
        return Collections.singletonList(new ParsedRequestDto(
                "Raw_GraphQL_Query", "POST", null, payload, Map.of(), payload));
    }
}
