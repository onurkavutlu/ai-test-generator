package com.testgen.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cURL ayrıştırması.
 *
 * <p><b>Kapatılan gerçek arıza:</b> Kullanıcı SOAP çağrısını {@code -X POST} yazmadan,
 * yalnızca {@code --data} ile verdi. Eski kod metodu sadece {@code -X} bayrağından
 * okuduğu için isteği <b>GET</b> sandı; başlıkları ve gövdeyi de yok sayarak SOAP
 * ucuna boş bir GET attı. Yanıt alınamayınca gözlem "hedefe erişilemedi" dedi ve
 * ajanlar bunu "endpoint erişilemez" diye okuyup tüm analizi yanlış öncül üzerine
 * kurdu.
 */
class CurlParserTest {

    private final CurlParser parser = new CurlParser();

    private static final String SOAP_CURL = """
            curl --location 'https://ic-servis.ornek.local/Servis/listenEndPointURI' \\
            --header 'Content-Type: text/xml; charset=utf-8' \\
            --header 'SOAPAction: http://ornek/v2/sorgula' \\
            --data '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                <soapenv:Body>
                    <v1:input><pageNo>1</pageNo></v1:input>
                </soapenv:Body>
            </soapenv:Envelope>'
            """;

    @Test
    @DisplayName("-X yoksa ve gövde varsa metot POST'tur, GET değil")
    void bodyImpliesPostWithoutExplicitFlag() {
        ParsedRequestDto r = parser.parse(SOAP_CURL);

        assertNotNull(r);
        assertEquals("POST", r.method(),
                "Gövdeli cURL GET sanıldı — gözlem yanlış istek gönderir");
    }

    @Test
    @DisplayName("Başlıklar ayrıştırılır — SOAPAction ve Content-Type kaybolmaz")
    void headersAreParsed() {
        ParsedRequestDto r = parser.parse(SOAP_CURL);

        assertEquals("text/xml; charset=utf-8", r.headers().get("Content-Type"));
        assertEquals("http://ornek/v2/sorgula", r.headers().get("SOAPAction"));
    }

    @Test
    @DisplayName("Çok satırlı gövde bütün olarak ayrıştırılır")
    void multilineBodyIsCaptured() {
        ParsedRequestDto r = parser.parse(SOAP_CURL);

        assertNotNull(r.body());
        assertTrue(r.body().contains("<soapenv:Envelope"), r.body());
        assertTrue(r.body().contains("</soapenv:Envelope>"), r.body());
        assertTrue(r.body().contains("<pageNo>1</pageNo>"), r.body());
    }

    @Test
    @DisplayName("URL --location bayrağından okunur")
    void urlIsParsed() {
        ParsedRequestDto r = parser.parse(SOAP_CURL);

        assertEquals("https://ic-servis.ornek.local/Servis/listenEndPointURI", r.url());
    }

    @Test
    @DisplayName("Açık -X bayrağı gövdeden önce gelir")
    void explicitMethodWins() {
        ParsedRequestDto r = parser.parse(
                "curl -X PUT 'https://ornek.local/kayit/1' --data '{\"a\":1}'");

        assertEquals("PUT", r.method());
    }

    @Test
    @DisplayName("Gövde yoksa metot GET kalır")
    void noBodyMeansGet() {
        ParsedRequestDto r = parser.parse("curl --location 'https://ornek.local/liste'");

        assertEquals("GET", r.method());
        assertNull(r.body());
    }

    @Test
    @DisplayName("-I metodu HEAD yapar")
    void headFlagSelectsHead() {
        ParsedRequestDto r = parser.parse("curl -I 'https://ornek.local/health'");

        assertEquals("HEAD", r.method());
    }

    @Test
    @DisplayName("URL yoksa null döner — uydurma URL üretilmez")
    void missingUrlReturnsNull() {
        assertNull(parser.parse("curl -X POST --data '{}'"));
        assertNull(parser.parse(""));
        assertNull(parser.parse(null));
    }

    @Test
    @DisplayName("Güvenli metotlar yalnızca GET/HEAD/OPTIONS")
    void safeMethods() {
        assertTrue(CurlParser.isSafeMethod("GET"));
        assertTrue(CurlParser.isSafeMethod("head"));
        assertTrue(CurlParser.isSafeMethod("OPTIONS"));
        org.junit.jupiter.api.Assertions.assertFalse(CurlParser.isSafeMethod("POST"));
        org.junit.jupiter.api.Assertions.assertFalse(CurlParser.isSafeMethod("DELETE"));
        org.junit.jupiter.api.Assertions.assertFalse(CurlParser.isSafeMethod(null));
    }
}
