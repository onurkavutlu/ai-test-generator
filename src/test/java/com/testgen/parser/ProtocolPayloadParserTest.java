package com.testgen.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolPayloadParserTest {

    @Test
    void graphQlJsonDoesNotInventEndpoint() {
        ParsedRequestDto parsed = new GraphQLParser(new ObjectMapper()).parse("""
                {"operationName":"ListPets","query":"query { pets { id } }","variables":{}}
                """).get(0);

        assertEquals("ListPets", parsed.name());
        assertEquals("POST", parsed.method());
        assertNull(parsed.url(), "GraphQL payload endpoint taşımıyorsa /graphql uydurulmamalı");
        assertTrue(parsed.body().contains("\"operationName\":\"ListPets\""));
        assertTrue(parsed.headers().isEmpty(), "Payload'da header yoksa parser header uydurmamalı");
    }

    @Test
    void rawGraphQlDoesNotInventEndpoint() {
        ParsedRequestDto parsed = new GraphQLParser(new ObjectMapper())
                .parse("query { pets { id } }").get(0);

        assertEquals("Raw_GraphQL_Query", parsed.name());
        assertNull(parsed.url());
        assertEquals("query { pets { id } }", parsed.body());
    }

    @Test
    void malformedGraphQlJsonPreservesExactRawBodyWithoutInventingEndpoint() {
        String malformed = "{\"query\": \"query { pets { id } }\"";

        ParsedRequestDto parsed = new GraphQLParser(new ObjectMapper()).parse(malformed).get(0);

        assertEquals("Raw_GraphQL_Query", parsed.name());
        assertNull(parsed.url());
        assertEquals(malformed, parsed.body());
    }

    @Test
    void soapEnvelopeDoesNotInventEndpoint() {
        ParsedRequestDto parsed = new SoapXmlParser().parse("""
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                  <soapenv:Body><pet:GetPet xmlns:pet="urn:pets"/></soapenv:Body>
                </soapenv:Envelope>
                """).get(0);

        assertEquals("GetPet", parsed.name());
        assertEquals("POST", parsed.method());
        assertNull(parsed.url(), "SOAP envelope endpoint taşımıyorsa /soap-endpoint uydurulmamalı");
        assertTrue(parsed.body().contains("<pet:GetPet"));
        assertTrue(parsed.headers().isEmpty(), "SOAPAction/Content-Type payload'da yoksa uydurulmamalı");
    }
}
