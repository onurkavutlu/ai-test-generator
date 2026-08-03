package com.testgen.parser;

public record ParsedRequestDto(
        String name,
        String method,
        String url,
        String payloadDetails
) {}
