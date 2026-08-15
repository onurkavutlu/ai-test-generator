@publicapi @testCaseLLM
Feature: restful-api.dev — Objects API sözleşme testleri

  # ══════════════════════════════════════════════════════════════════════════
  # KAYNAK SEÇİMİ
  # public-apis listesinden restful-api.dev seçildi. Gerekçe:
  #   - Kimlik doğrulama YOK  → anahtar paylaşmadan koşar
  #   - TAM CRUD (GET/POST/PUT/PATCH/DELETE) → durum geçişi senaryoları yazılabilir
  #   - Kaynak oluşturma serbest → negatif ve sınır senaryoları gerçek veriyle denenir
  # Yalnızca okuma yapan API'ler (catfact, jsonplaceholder) bu senaryoların
  # yarısını yazdırmaya izin vermezdi.
  #
  # ISTQB teknikleri senaryo başlıklarında etiketlidir:
  #   EP = Denklik Sınıfları, BVA = Sınır Değer, ST = Durum Geçişi, EG = Hata Tahminleme
  # ══════════════════════════════════════════════════════════════════════════

  Background:
    * url 'https://api.restful-api.dev'
    * configure connectTimeout = 10000
    * configure readTimeout = 20000
    * def uniqueName = 'AI-TestGen-' + java.lang.System.currentTimeMillis()

  # ── Fonksiyonel: pozitif ────────────────────────────────────────────────

  @smoke
  Scenario: [SMOKE][P0_BLOCKER][EP] Tüm nesneler listelenir
    Given path 'objects'
    When method GET
    Then status 200
    And match response == '#[_ > 0]'
    And match each response contains { id: '#string', name: '#string' }

  @smoke
  Scenario: [SMOKE][P0_BLOCKER][EP] Tek nesne kimliğiyle getirilir
    Given path 'objects', '1'
    When method GET
    Then status 200
    And match response.id == '1'
    And match response.name == '#string'

  @regression
  Scenario: [REGRESSION][P1_CRITICAL][EP] Birden fazla nesne tek istekte getirilir
    Given path 'objects'
    And param id = 1
    And param id = 2
    When method GET
    Then status 200
    And match response == '#[2]'
    And match response[*].id contains '1'
    And match response[*].id contains '2'

  # ── Fonksiyonel: durum geçişi (create → read → update → delete) ─────────

  @regression @e2e
  Scenario: [E2E][P1_CRITICAL][ST] Nesne yaşam döngüsü: oluştur → oku → güncelle → sil
    # 1) Oluştur
    Given path 'objects'
    And request { name: '#(uniqueName)', data: { yil: 2026, fiyat: 1499.99, uretici: 'AI Test Generator' } }
    When method POST
    Then status 200
    And match response.id == '#string'
    And match response.name == uniqueName
    * def olusanId = response.id

    # 2) Oku — yazılan veri gerçekten okunabilmeli
    Given path 'objects', olusanId
    When method GET
    Then status 200
    And match response.name == uniqueName
    And match response.data.yil == 2026

    # 3) Güncelle (tam değiştirme)
    Given path 'objects', olusanId
    And request { name: '#(uniqueName + "-guncel")', data: { yil: 2027, fiyat: 1599.99, uretici: 'AI Test Generator' } }
    When method PUT
    Then status 200
    And match response.name == uniqueName + '-guncel'
    And match response.data.yil == 2027

    # 4) Kısmi güncelle
    Given path 'objects', olusanId
    And request { name: '#(uniqueName + "-patch")' }
    When method PATCH
    Then status 200
    And match response.name == uniqueName + '-patch'

    # 5) Sil
    Given path 'objects', olusanId
    When method DELETE
    Then status 200

    # 6) Silinen kaynak artık okunamamalı — silme GERÇEKTEN oldu mu?
    Given path 'objects', olusanId
    When method GET
    Then status 404

  # ── Fonksiyonel: negatif ────────────────────────────────────────────────

  @negative
  Scenario: [NEGATIVE][P1_CRITICAL][EG] Var olmayan kimlik 404 döner
    Given path 'objects', 'bu-kimlik-yok-999999'
    When method GET
    Then status 404

  @negative
  Scenario: [NEGATIVE][P2_MAJOR][ST] Var olmayan kaynağı silmek 404 döner
    Given path 'objects', 'bu-kimlik-yok-999999'
    When method DELETE
    Then status 404

  @negative
  Scenario: [NEGATIVE][P2_MAJOR][EG] Var olmayan kaynağı güncellemek 404 döner
    Given path 'objects', 'bu-kimlik-yok-999999'
    And request { name: 'olmayan' }
    When method PUT
    Then status 404

  # ── Sınır değer analizi ─────────────────────────────────────────────────

  @boundary
  Scenario: [BOUNDARY][P2_MAJOR][BVA] Çok uzun isimle nesne oluşturulur
    * def uzunIsim = uniqueName + '-' + 'x'.repeat(500)
    Given path 'objects'
    And request { name: '#(uzunIsim)', data: { not: 'sinir degeri testi' } }
    When method POST
    Then status 200
    And match response.name == uzunIsim
    # Temizlik: test verisi bırakma
    * def olusanId = response.id
    Given path 'objects', olusanId
    When method DELETE
    Then status 200

  @boundary
  Scenario: [BOUNDARY][P3_MINOR][BVA] Boş data nesnesiyle kayıt oluşturulur
    Given path 'objects'
    And request { name: '#(uniqueName + "-bos-data")', data: {} }
    When method POST
    Then status 200
    And match response.name == uniqueName + '-bos-data'
    * def olusanId = response.id
    Given path 'objects', olusanId
    When method DELETE
    Then status 200

  # ── Fonksiyonel olmayan ─────────────────────────────────────────────────

  @performance
  Scenario: [PERFORMANCE][P2_MAJOR][BVA] Liste ucu kabul edilebilir sürede yanıt verir
    Given path 'objects'
    When method GET
    Then status 200
    # SLA: dış servis olduğu için toleranslı; amaç zaman aşımı regresyonunu yakalamak
    And assert responseTime < 10000

  @regression
  Scenario: [REGRESSION][P2_MAJOR][EP] Yanıt JSON içerik tipiyle döner
    Given path 'objects', '1'
    When method GET
    Then status 200
    And match header Content-Type contains 'application/json'
