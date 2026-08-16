package com.testgen.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SoapXmlParser {

    // Body içindeki ilk elemanı yakalamak için basit bir regex
    // Örnek: <soapenv:Body><ns:GetNumberRequest>... -> GetNumberRequest yakalar
    private static final Pattern BODY_PATTERN = Pattern.compile("<[^:]+:Body>\\s*<([^\\s>]+)", Pattern.CASE_INSENSITIVE);

    public List<ParsedRequestDto> parse(String xmlPayload) {
        String operationName = "SoapOperation";
        
        try {
            Matcher matcher = BODY_PATTERN.matcher(xmlPayload);
            if (matcher.find()) {
                String tag = matcher.group(1);
                // Eğer tag namespace içeriyorsa (ns1:GetStock) namespace'i atalım
                if (tag.contains(":")) {
                    tag = tag.substring(tag.indexOf(":") + 1);
                }
                operationName = tag;
            }
        } catch (Exception e) {
            log.warn("SOAP operasyon adı parse edilemedi: {}", e.getMessage());
        }
        
        // SOAP envelope endpoint taşımaz; sabit bir yol uydurmak yerine hedef çağırandan gelir.
        return Collections.singletonList(new ParsedRequestDto(
                operationName, "POST", null, xmlPayload, Map.of(), xmlPayload));
    }
}
