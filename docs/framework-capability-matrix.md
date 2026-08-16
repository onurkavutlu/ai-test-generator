# Framework Capability Matrix

Bu matris yalnız mevcut kaynak kod ve otomatik testlerle doğrulanan davranışı gösterir.
`Destekleniyor` ifadesi, ilgili generator yolunun girdiyi gerçekten ayrıştırdığı anlamına gelir;
generic user-story fallback'i destek olarak sayılmaz.

| Girdi | Karate | REST Assured | Selenium | Kanıt / kısıt |
|---|---|---|---|---|
| Backend user story | Destekleniyor | Destekleniyor | Uygulanamaz | LLM tabanlı; gözlenmeyen endpoint assertion'ı üretmemelidir |
| OpenAPI URL/dokümanı | Destekleniyor | Destekleniyor | Uygulanamaz | Operasyon başına üretim ve `maxCases` testlidir |
| cURL | Destekleniyor | Destekleniyor | Uygulanamaz | Metot, URL, header ve body `CurlParser` ile ayrıştırılır |
| Captured response | Destekleniyor | Destekleniyor | Uygulanamaz | Gözlenen facts varsa LLM'den bağımsız deterministik case eklenir |
| Postman/API collection | Destekleniyor | Destekleniyor | Uygulanamaz | Her istek yapılandırılmış metot/URL/header/body ile taşınır; `maxCases` uygulanır; çözümlenmemiş mutlak URL kontrollü reddedilir |
| HAR | Destekleniyor | Destekleniyor | Uygulanamaz | Kaydedilmiş istek alanları ve HAR yanıt kanıtı taşınır; yalnız JSON/XML API girdileri parser kapsamındadır |
| GraphQL payload | Koşullu destek | Kontrollü ret | Uygulanamaz | Karate için gerçek mutlak endpoint `applicationUrl` içinde zorunludur |
| SOAP envelope | Koşullu destek | Kontrollü ret | Uygulanamaz | Karate için gerçek mutlak endpoint `applicationUrl` içinde zorunludur |
| Endpoint/metot taşımayan raw JSON | Kontrollü ret | Kontrollü ret | Uygulanamaz | Metot/endpoint uydurulmaz; normalize HTTP girdi sözleşmesi beklenir |
| Web URL + user story | Uygulanamaz | Uygulanamaz | Destekleniyor | `applicationUrl` zorunludur |
| Gözlenen DOM/page | Uygulanamaz | Uygulanamaz | Destekleniyor | Yalnız gözlenen title ve element id'lerinden deterministik smoke case |

## Otomatik doğrulama kaynakları

- `KarateTestGeneratorDispatchTest`: Karate girdi yönlendirmesi ve kontrollü retler.
- `RestAssuredTestGeneratorTest`: OpenAPI, user story, cURL ve observed-response yolları.
- `ProtocolPayloadParserTest`: GraphQL/SOAP parser'larının endpoint uydurmadığı sözleşme.
- `NoFabricatedContentTest`: gözlenmeyen adres/status/SLA üretmeme sözleşmesi.
- `SeleniumTestGeneratorTest`: gerçek URL ve gözlenen DOM tabanlı üretim.

## Açık parite işleri

1. GraphQL ve SOAP için endpoint, headers, body ve gözlenen response'u ortak modelde toplamak; ardından REST Assured renderer eklemek.
2. Collection/HAR yollarını ortak normalize HTTP modeline taşıyarak Karate ve REST Assured arasındaki yinelenen dispatch kodunu kaldırmak.
3. Raw JSON için endpoint ve HTTP metodunu açık alanlarla alan normalize girdi sözleşmesini tamamlamak.
4. Generator içindeki dosya yazma yan etkisini `ArtifactBundle -> validate -> persist` sırasına taşımak.
