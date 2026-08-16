# AI Test Generator — Mimari Sağlamlaştırma Yol Haritası

## Karar ve kapsam

Bu belge, çalışmanın sırasını iki faza ayırır:

- **Faz 1 — Çekirdek ürün ve framework mimarisi:** Karate, REST Assured ve Selenium için girdiden çalıştırma sonucuna kadar tutarlı, doğrulanabilir ve framework'ler arasında dengeli bir test yaşam döngüsü kurulur.
- **Faz 2 — Kurumsallaştırma ve OCP production hazırlığı:** Güvenlik, tenant izolasyonu, dağıtık iş yönetimi, OCP sertleştirmesi, veri yönetişimi, gözlemlenebilirlik ve CI/CD kapıları tamamlanır.

Önceki incelemelerde bulunan güvenlik, OCP ve kurumsal işletim maddelerinin tamamı Faz 2 backlog'undadır. Faz 1'in tamamlanması, tek başına production için `GO` anlamına gelmez.

Tüm geliştirme, doğrulama ve çalışma ortamlarında Java 17 kullanılacaktır.

## Uygulama ilerlemesi

- **F1.2.a — Framework generator seçimi tamamlandı:** `TestGenerationService` artık Karate, REST Assured ve Selenium sınıflarını doğrudan tanımıyor. Ortak `FrameworkTestGenerator` sözleşmesi ve başlangıçta eksik/çift kaydı reddeden registry üzerinden çalışıyor.
- **F1.2.a doğrulaması:** Java 17 ile registry ve generation service testleri geçti. Güncel deterministik `verify` suite'i 598 testle hatasızdır; canlı public API testleri ayrı profilde izlenir.
- **F1.2.b — Agent yürütme sözleşmesi tamamlandı:** Bütün roller başlangıçta eksik/çift kaydı reddeden `AiAgentRegistry` üzerinden çözülüyor. Plan dışı tool çağrısı çalıştırılmıyor, aynı ajan iki kez koşturulmuyor ve supervisor zorunlu rollerin yalnızca bir bölümünü çağırırsa eksikler deterministik sırayla tamamlanıyor. Zorunlu ajan hatası üretimi durduruyor; opsiyonel ajan hatası açık logla izleniyor.
- **F1.2.b — Kanıt ve ölçüm sınırı:** Serbest supervisor sentezi üretim girdisine kanıt olarak eklenmiyor; yalnız gerçekten çalışmış ajan çıktıları taşınıyor. Agent prompt'ları gözlenmeyen endpoint, selector, veri, status, SLA veya ortam bilgisi üretmeyi açıkça yasaklıyor. A/B benchmark LLM maliyeti zaman penceresiyle değil doğrudan generation `requestId` korelasyonuyla hesaplanıyor.
- **F1.2.b doğrulaması:** Agent paketinin satır kapsamı `%96,04` (`194/202`) ve branch kapsamı `%86,59`; satır kapsamı Maven'da `%90` paket kapısıyla kilitlendi.
- **F1.1 capability matrisi oluşturuldu:** Framework/girdi yolları generic fallback'i destek saymadan kaynak ve test kanıtıyla `docs/framework-capability-matrix.md` içinde sınıflandırıldı.
- **F1.3 raw/protokol güvenilirliği:** REST Assured cURL/CAPTURED, Postman Collection ve HAR girdilerinde parser'dan gelen gerçek metot, URL, header ve body'yi taşıyor. HAR'ın kaydedilmiş status/yanıt özeti kaynak kanıtı olarak prompt'a ekleniyor. Ayrıştırılamayan girdi veya çözümlenmemiş mutlak HTTP(S) URL generic teste düşmek yerine kontrollü reddediliyor. GraphQL ve SOAP Karate üretimi açık mutlak `applicationUrl` olmadan başlamıyor; `/graphql` ve `/soap-endpoint` varsayımları kaldırıldı.
- **F1.5.a — REST Assured unit-test tabanı:** User story, gözlenen yanıt, yerel OpenAPI, operasyon limiti, cURL, Postman Collection, HAR, geçersiz girdi/spec ve artifact yazma yolları unit testlerle kapsandı. Generator için `%90` sınıf kapsam kapısı aktiftir.
- **F1.8.a — Ölçümlü test raporu:** `scripts/test-with-report.sh`, her Maven koşumunu Java 17 ile sınırlar; güncel Surefire/Failsafe XML ve izole JaCoCo verisinden kalıcı Markdown raporu üretir. Eski kapsam verisi yeni koşuma eklenmez; atlama nedenleri XML'den alınır.
- **F1.10 coverage hedefi:** Proje genelinde ölçülen başlangıç satır kapsamı `%76,18`, branch kapsamı `%61,16`; güncel ölçüm satır `%80,58` (`4556/5654`), branch `%64,02` seviyesindedir. Genel kalite kapısı satır `%75`, branch `%60`; nihai hedef en az `%90` satır kapsamıdır. Kapsam, işlevsel test eklenmeden exclude veya yapay çağrılarla yükseltilmeyecektir.
- **F1.10 deterministik kalite kapısı:** Varsayılan `verify` canlı internete bağlı `external` testleri dışlar. Canlı public API testleri `-Pexternal-tests` profiliyle ayrıca çalıştırılır ve sonuçları zorunlu yerel/CI suite'inden ayrı raporlanır.
- **F1.10 Java kalite kapısı:** Maven Enforcer `[17,18)` aralığıyla Java 17 dışındaki JDK'larda build'i başlamadan reddeder; compiler source/target değeri de `17` olarak kalır.
- **Sonraki çekirdek adım:** F1.1 capability/kabul matrisi ve deterministik fixture sınırı; ardından ortak normalize girdi modeli.

Bu ilerleme Faz 1'in tamamlandığı veya uygulamanın production'a hazır olduğu anlamına gelmez.

## Mevcut durumun kaynak kodla doğrulanan özeti

| Alan | Mevcut durum | Faz 1 ihtiyacı |
|---|---|---|
| Karate | OpenAPI, API collection, HAR, GraphQL, SOAP, ham payload, gözlem ve user-story yolları var | Dağınık dalları ortak girdi ve artifact sözleşmesine taşımak |
| REST Assured | OpenAPI, cURL/CAPTURED, Postman Collection, HAR, user-story ve gözlem yolu var | GraphQL/SOAP paritesini ve ortak normalize renderer mimarisini tamamlamak |
| Selenium | User-story + URL + gözlenen sayfa üzerinden Java/POM üretimi var | Test ve Page Object'leri tek kalıcı artifact paketi yapmak |
| Doğrulama | Karate parse, Java compile ve LLM repair var | Framework sonucunu tek tip raporlamak ve doğrulamayı side-effect'siz yapmak |
| Çalıştırma | Karate in-process; Java testleri Maven subprocess ile | Framework bağımsız execution contract ve yapılandırılmış sonuç üretmek |
| Dosya sistemi | Generator'lar doğrulama tamamlanmadan dosya yazıyor | `generate -> validate -> persist -> execute` sırasını kurmak |
| Test kapsamı | Genel kapsam yeterli başlangıç düzeyinde; REST Assured generator doğrudan çok az test edilmiş | Framework bazlı contract, golden ve E2E test matrisi oluşturmak |

## Faz 1 hedef mimarisi

```text
GenerationCommand
       |
       v
InputAdapter -> NormalizedTestIntent + ObservedEvidence
       |
       v
ScenarioPlanner
       |
       v
FrameworkRenderer (Karate | REST Assured | Selenium)
       |
       v
ArtifactBundle (test + destek dosyaları + metadata)
       |
       v
FrameworkValidator
       |
       v
ArtifactRepository
       |
       v
ExecutionEngine -> StructuredExecutionResult
```

### Temel mimari kuralları

1. Girdi ayrıştırma framework generator'larından bağımsız olacaktır.
2. Gözlem sonucu serbest metin/regex yerine yapılandırılmış kanıt modeliyle taşınacaktır.
3. Generator'lar dosya sistemine veya veritabanına yazmayacak; yalnız artifact döndürecektir.
4. Bir test ve kullandığı Page Object/destek sınıfları aynı `ArtifactBundle` içinde tutulacaktır.
5. Doğrulama ve çalıştırma framework SPI'ları üzerinden yapılacaktır.
6. Süreç sonucu Maven konsol metnine bağlı kalmadan yapılandırılmış biçimde saklanacaktır.
7. Somut girdi ayrıştırılamadığında ilgisiz generic teste sessiz fallback yapılmayacaktır.
8. Gözlenmeyen status, alan, selector, URL veya SLA üretilmeyecektir.
9. Framework'e özel bağımlılıklar birbirinden ayrılacaktır.
10. Faz 1 refaktörü mevcut public API'yi kontrollü biçimde koruyacak; zorunlu değişiklikler sürümlendirilecektir.

## Faz 1 iş paketleri

### F1.1 — Davranış envanteri ve kabul matrisi

- Mevcut üretim girdileri için golden fixture seti oluştur.
- Her framework'ün desteklediği kombinasyonları açık capability matrisi olarak tanımla.
- Başarı, kontrollü ret ve desteklenmeyen kombinasyon sonuçlarını ayır.
- Mevcut 598 testlik Java 17 baseline'ını regresyon başlangıcı olarak sabitle.
- Framework bazlı kapsama raporu üret; yalnız toplam proje kapsamını kullanma.

**Çıkış kriteri:** Her girdi/framework kombinasyonunun beklenen davranışı otomatik testte tanımlı olmalı; belirsiz fallback kalmamalı.

### F1.2 — Ortak domain ve pipeline sözleşmeleri

Aşağıdaki tipleri framework bağımsız domain olarak oluştur:

- `GenerationCommand`
- `InputSource` ve enum tabanlı `InputType`
- `NormalizedTestIntent`
- `TargetDefinition`
- `ObservedExchange` / `ObservedPage`
- `ScenarioSpec`
- `TestArtifact` ve `ArtifactBundle`
- `ValidationReport`
- `ExecutionRequest`
- `StructuredExecutionResult`

Aşağıdaki SPI'ları tanımla:

- `InputAdapter`
- `ScenarioPlanner`
- `FrameworkRenderer`
- `FrameworkValidator`
- `ArtifactRepository`
- `ExecutionEngine`

**Çıkış kriteri:** Controller veya orchestration servisi somut Karate/REST Assured/Selenium generator sınıfını doğrudan seçmek zorunda kalmamalı.

### F1.3 — Ortak girdi normalizasyonu

- OpenAPI URL/dokümanını bir kez ayrıştır ve bütün backend renderer'larına aynı normalize modeli ver.
- cURL/captured response, API collection, HAR, GraphQL ve SOAP XML girdilerini ortak HTTP/operation modeline dönüştür.
- URL, method, headers, query/path parametreleri, body, content type, auth izi ve gözlenen yanıtı ayrı alanlarda taşı.
- `payloadType` serbest metnini enum tabanlı tipe geçir.
- Parse hatasında input'a dayanmayan generic test üretme; açık ve izlenebilir hata döndür.

**Çıkış kriteri:** Aynı normalize backend girdisi hem Karate hem REST Assured renderer'ı tarafından tüketilebilmeli.

### F1.4 — Karate mimarisini sağlamlaştırma

- Karate üretimini `FrameworkRenderer` sözleşmesine taşı.
- OpenAPI, cURL/captured, collection, HAR, GraphQL ve SOAP yollarını ortak normalize modelden üret.
- Header, cookie, path/query parametreleri, request body, content type ve gözlenen assertion'ları deterministik biçimde derle.
- Feature/scenario isimlerini çakışmasız ve tekrarlanabilir üret.
- Gherkin/Karate parser doğrulamasını renderer'dan ayır.
- Parse edilen senaryo sayısı ile çalıştırılan senaryo sayısını karşılaştır.
- Karate sonucu için scenario bazlı status, süre ve hata üret.
- Aynı girdi için tekrarlanabilir golden-output testleri ekle.

**Çıkış kriteri:** Desteklenen her backend girdi türü için en az bir parse edilen ve yerel fixture'a karşı geçen Karate testi bulunmalı.

### F1.5 — REST Assured mimarisini sağlamlaştırma

- REST Assured üretimini aynı `FrameworkRenderer` sözleşmesine taşı.
- OpenAPI dışında cURL/captured, collection, HAR, GraphQL ve SOAP XML için uygulanabilir backend paritesini tamamla.
- JUnit 5 sınıfı, method adı, package ve dosya adını deterministik üret.
- Path/query/header/cookie/body/auth bilgilerini normalize modelden derle.
- JSON ve XML response assertion'larını gözlenen kanıttan üret.
- REST Assured Maven şablonundan Selenium ve WebDriverManager bağımlılıklarını çıkar.
- Java 17 compile ve gerçek Maven execution contract testleri ekle.
- Surefire XML'i birincil sonuç kaynağı yap; konsol regex'ini fallback seviyesine indir.
- REST Assured generator için doğrudan unit, contract ve E2E kapsamı oluştur.

**Çıkış kriteri:** Capability matrisindeki bütün REST Assured yolları Java 17 ile derlenmeli ve yerel fixture servisinde doğru sonucu vermeli.

### F1.6 — Selenium mimarisini sağlamlaştırma

- Selenium renderer'ını ortak pipeline'a taşı.
- Test sınıfı, Page Object ve gerekli destek sınıflarını tek `ArtifactBundle` olarak üret ve kalıcılaştır.
- Çalıştırmanın pod/local disk üzerinde daha önce kalmış destek dosyalarına bağımlılığını kaldır.
- Selector'ları yalnız gözlenen DOM kanıtından veya açık kullanıcı girdisinden üret.
- Locator tercih sırasını tanımla: test-id/id/name/erişilebilir rol veya etiket, ardından kontrollü CSS; kırılgan XPath son seçenek.
- Driver lifecycle'ı tek altyapı bileşeninde tut; test sınıflarına sürücü kurulum kodu yazdırma.
- Explicit wait politikasını standartlaştır; rastgele sleep üretimini reddet.
- Remote Selenium Grid ve kontrollü local driver yolları için aynı execution sözleşmesini kullan.
- Hata halinde screenshot, URL, title ve sınırlı page-source artifact'ı üret.
- Java 17 compile, fixture web uygulaması ve Grid tabanlı E2E testleri ekle.

**Çıkış kriteri:** Test + Page Object paketi uygulama restart'ından bağımsız biçimde yeniden doğrulanıp çalıştırılabilmeli; selector'ların kanıt kaynağı izlenebilmeli.

### F1.7 — Doğrulama mimarisi

- Karate parser ve Java compiler sonuçlarını ortak `ValidationReport` modeline geçir.
- `VALID`, `INVALID`, `SKIPPED` sonucuna neden kodu ve validator sürümü ekle.
- Renderer çıktısını doğrulama tamamlanmadan diske/kalıcı depoya yazma.
- LLM repair öncesi ve sonrası artifact hash'lerini kaydet.
- Framework bağımlılığı eksikliği ile test kodu hatasını birbirinden ayır.
- Manuel ve otomatik üretim için aynı doğrulama servisinin kullanılabileceği extension point'i tamamla; güvenlik/politika zorlamaları Faz 2'de etkinleştirilecek.

**Çıkış kriteri:** Aynı artifact aynı validator ortamında tekrarlanabilir sonuç vermeli; `SKIPPED` sonucu başarı sayılmamalı.

### F1.8 — Çalıştırma ve sonuç mimarisi

- `ExecutionEngine` arayüzü altında Karate ve Java/Maven adapter'larını oluştur.
- Local engine'i framework mimarisinin referans implementasyonu yap; OCP Job engine Faz 2'de eklenecek.
- Tek test, request, suite, plan ve rerun akışlarının aynı execution orchestration katmanını kullanmasını sağla.
- Runner sonucunu scenario/test bazında yapılandırılmış kaynaktan oku.
- Artifact hash, framework sürümü, Java sürümü, hedef ve çalışma parametrelerini execution kaydına bağla.
- Duplicate çalıştırmayı önlemek için execution command kimliğini sözleşmeye ekle; dağıtık idempotency Faz 2'de tamamlanacak.

**Çıkış kriteri:** Üç framework aynı execution durum makinesini ve aynı sonuç modelini kullanmalı; UI raporu framework'e özel log parse etmek zorunda kalmamalı.

### F1.9 — API ve kullanıcı akışı tutarlılığı

- Generate, generate-from-response ve manuel test akışlarını aynı application service'e bağla.
- API'nin desteklenmeyen framework/girdi kombinasyonunu başlamadan reddetmesini sağla.
- Her async işlem için takip edilebilir operation/execution kimliği döndür.
- Üretim tamamlanmadan otomatik çalıştırmanın başlamadığını contract testle doğrula.
- Testin kanıt kaynağını, validation sonucunu ve artifact paketini API'de açıkça göster.

**Çıkış kriteri:** Aynı işlev farklı controller'lardan çağrıldığında farklı üretim veya çalıştırma davranışı göstermemeli.

### F1.10 — Faz 1 test ve kalite kapısı

- Java 17 zorunlu Maven Enforcer/toolchain kontrolü ekle.
- Framework başına unit, contract, integration ve E2E test seti oluştur.
- Deterministik fixture HTTP servisi ve fixture web uygulaması kullan.
- Ağ erişimine bağlı public testleri zorunlu kalite kapısından ayır; skip nedeni ölçülebilir olsun.
- Golden artifact testlerinde anlamsız whitespace yerine semantik karşılaştırma kullan.
- Framework bazında minimum kapsama eşiği tanımla; özellikle REST Assured generator boşluğu kapatılmadan Faz 1'i bitirme.

**Faz 1 nihai kabul kapısı:**

- Karate, REST Assured ve Selenium capability matrisindeki tüm zorunlu akışlar yeşil.
- Üretilen zorunlu artifact'ların tamamı parse/compile doğrulamasından geçiyor.
- Üç framework de gerçek fixture hedef üzerinde çalıştırılıyor ve yapılandırılmış sonuç üretiyor.
- Gözlenmeyen veriyle assertion/selector üreten golden test yok.
- Generator katmanında dosya sistemi veya DB yan etkisi yok.
- Test ve destek artifact'ları birlikte, yeniden üretilebilir biçimde saklanıyor.
- Java 17 dışındaki JDK ile build kalite kapısı geçmiyor.

## Faz 2 backlog — kurumsallaştırma ve OCP production hazırlığı

Önceki incelemelerde belirlenen maddelerin tamamı aşağıdaki Faz 2 iş akışlarına atanmıştır.

### F2.1 — Kimlik, yetki ve tenant izolasyonu

- OIDC entegrasyonu, RBAC, resource ownership ve tenant/project sınırları.
- Audit trail ve kullanıcı/servis hesabı ayrımı.
- Öğrenme kayıtlarının tenant + project + environment + service kapsamında izolasyonu.
- Hassas request, response, prompt, test ve log alanlarının maskeleme/şifreleme politikası.

### F2.2 — Outbound güvenliği ve kötüye kullanım önleme

- Bütün HTTP yollarında ortak redirect güvenliği ve her hop doğrulaması.
- Host değişiminde Authorization/Cookie/API-key başlıklarının düşürülmesi.
- DNS rebinding, metadata, private-network ve kontrollü egress politikaları.
- Streaming response boyut limiti, decompression limiti ve content-type politikası.
- Rate limit, maliyet kotası, concurrency kotası ve request boyut sınırları.

### F2.3 — Dosya ve çalışma güvenliği

- Dosya adı/path traversal savunması ve canonical path doğrulaması.
- Manuel testlerin zorunlu validation kapısı.
- `INVALID`/`NOT_VALIDATED` testlerin çalıştırılmasının engellenmesi.
- Kullanıcı/LLM kaynaklı kodun API podundan çıkarılması.
- Kısa ömürlü, limitsiz yetkisi olmayan OCP runner Job'ları.

### F2.4 — Dağıtık iş ve scheduler mimarisi

- Pod içi `@Async` yerine kalıcı iş kuyruğu ve worker modeli.
- Atomik job claim, idempotency, retry, dead-letter ve recovery.
- Optimistic locking ve açık state transition kuralları.
- Tek liderli scheduler veya OCP CronJob modeli.
- Subprocess timeout, bounded output ve process-tree sonlandırma.
- Bounded framework concurrency ve tenant bazlı kapasite yönetimi.

### F2.5 — OCP deployment sertleştirmesi

- Ayrı `application-ocp` profili; local/H2 fallback'in kapatılması.
- Arbitrary UID ve restricted SCC uyumlu image'lar.
- API JRE image ile runner JDK/browser image'larının ayrılması.
- Read-only root filesystem, capability drop, seccomp ve privilege escalation yasağı.
- Ayrı ServiceAccount, NetworkPolicy, PDB, quota ve topology kuralları.
- Immutable image digest, harici secret yönetimi ve kurumsal CA/proxy desteği.

### F2.6 — Veri ve artifact yönetişimi

- PostgreSQL için sürümlü migration; production'da `ddl-auto=validate`.
- Object/artifact storage ve kalıcı rapor modeli.
- Pagination, indeksleme ve hafif liste projeksiyonları.
- Retention, purge, KVKK/veri sınıflandırması ve silme akışları.
- Backup/restore ve disaster recovery doğrulaması.

### F2.7 — API, hata ve audit güvenilirliği

- RFC 7807 uyumlu dış hata modeli; iç exception detaylarının gizlenmesi.
- Bütün koşum tiplerinin kalıcı execution/audit kaydı.
- Async kabul yanıtlarının gerçek job varlığıyla doğrulanması.
- Çok podlu ortamda DB kaynaklı tutarlı LLM/ajan raporu.
- JPA entity equality/hashCode ve ilişki modelinin güvenli hale getirilmesi.

### F2.8 — Gözlemlenebilirlik ve işletim

- JSON stdout logları, correlation ve OpenTelemetry trace.
- ServiceMonitor, framework/job/LLM metrikleri ve SLO'lar.
- Gerçek readiness, güvenli liveness ve startup probe.
- Alarm, runbook, incident, kapasite ve maliyet takibi.

### F2.9 — CI/CD ve tedarik zinciri

- Güvenlik taramalarının gerçekten build kırması; `|| true` ve `exit-code 0` kaldırılması.
- Privileged Docker-in-Docker yerine kurum onaylı rootless build.
- TLS doğrulamasının zorunlu olması.
- SBOM, image imzalama, provenance ve immutable tool/image sürümleri.
- Pipeline'daki çoklu/tekrarlı test çalıştırmalarının kaldırılması.
- Karate ve Selenium üretimlerinin ayrı ayrı tamamlanmasının beklenmesi.
- Gerçek report endpoint ve artifact doğrulaması.
- Redirect cevabını başarı sanmayan smoke testler.

## Faz geçiş kuralları

1. Faz 1 capability matrisi ve kabul kapıları tamamlanmadan Faz 2 production çalışması başlamaz.
2. Faz 2 güvenlik maddeleri tamamlanmadan sistem paylaşımlı veya Internet'e açık OCP ortamına çıkarılmaz.
3. Her iş paketi kod, otomatik test, operasyon dokümanı ve ölçülebilir kabul kanıtıyla kapanır.
4. Geçici bypass, feature flag ile güvenlik kapatma veya dokümansız istisna kabul edilmez.
5. Süre tahmini; ekip kapasitesi, OCP sürümü, IdP, registry, storage ve LLM altyapısı netleşmeden verilmez.
