@btk @vpn
Feature: BTK Borç/Alacak — abonelik sorgulama (SOAP)

  # Bu servis VPN arkasındadır; testler yalnızca iç ağa erişimi olan bir makinede koşar.
  #
  # Koşum:
  #   ./mvnw test -Dtest=BtkBorcAlacakRunner
  #   ./mvnw test -Dtest=BtkBorcAlacakRunner -Dbtk.trIdentityNo=11111111111
  #
  # İstek gövdesi ayrı dosyadadır: data/abonelik-sorgula.xml
  # Gövde tek tırnakla satır içine YAZILMAZ — Karate `request` argümanını JavaScript
  # ifadesi olarak değerlendirir ve tek tırnaklı bir dize satır sonunu geçemez.
  # (Üretilen ilk sürüm tam bu yüzden "Missing close quote" ile düştü ve endpoint'e
  # hiç istek gitmedi.)

  Background:
    * url btkBaseUrl
    * path 'BTKBorcAlacak', 'listenEndPointURI'
    * header Content-Type = 'text/xml; charset=utf-8'
    * header SOAPAction = btkSoapAction
    * def payload = read('data/abonelik-sorgula.xml')

  # ────────────────────────────────────────────────────────────────
  # 1. Bağlantı ve sözleşme — önce buranın geçmesi gerekir
  # ────────────────────────────────────────────────────────────────

  @smoke
  Scenario: [SMOKE][P0_BLOCKER][EG] Geçerli sorgu SOAP yanıtı döndürür
    Given request payload
    When method POST
    Then status 200
    And match response !contains 'soapenv:Fault'
    # Yanıtın gerçek şeması ilk koşumdan sonra netleşir; keşif senaryosuna bak.

  @kesif
  Scenario: [EXPLORATORY][P2_MEDIUM][EXPLORATORY] Yanıtı olduğu gibi yazdır
    # Assertion YOK — amacı gerçek yanıtı görmek. Çıktıya bakıp yukarıdaki
    # senaryoya gerçek alan doğrulamaları eklenir. Değer uydurulmaz, ölçülür.
    Given request payload
    When method POST
    Then print 'HTTP status :', responseStatus
    And print 'Yanıt gövdesi:', response

  # ────────────────────────────────────────────────────────────────
  # 2. Girdi doğrulama — sınır değer ve eşdeğerlik sınıfları
  #    Beklenen hata kodları ilk koşumdan sonra doldurulur.
  # ────────────────────────────────────────────────────────────────

  @negatif
  Scenario Outline: [NEGATIVE][P1_CRITICAL][BVA] Geçersiz TC kimlik no: <aciklama>
    * def gecersiz = read('data/abonelik-sorgula.xml')
    * set gecersiz /Envelope/Body/subscriberInput/trIdentityNo = '<deger>'
    Given request gecersiz
    When method POST
    Then print '<aciklama> →', responseStatus, response
    # Servisin bu girdilerde ne döndürdüğü ölçülmeden assertion yazılmaz.

    Examples:
      | deger        | aciklama              |
      | 8101953614   | 10 hane (sınır altı)  |
      | 810195361480 | 12 hane (sınır üstü)  |
      |              | boş                   |
      | abcdefghijk  | sayısal değil         |

  @negatif
  Scenario: [NEGATIVE][P1_CRITICAL][EP] Hatalı parola reddedilmeli
    * def hatali = read('data/abonelik-sorgula.xml')
    * set hatali /Envelope/Body/subscriberInput/password = 'kesinlikle-yanlis'
    Given request hatali
    When method POST
    Then print 'Hatalı parola →', responseStatus, response
    And match response contains 'Fault'

  @negatif
  Scenario Outline: [NEGATIVE][P2_MEDIUM][BVA] Geçersiz sayfa numarası: <deger>
    * def sayfa = read('data/abonelik-sorgula.xml')
    * set sayfa /Envelope/Body/subscriberInput/pageNo = '<deger>'
    Given request sayfa
    When method POST
    Then print 'pageNo=<deger> →', responseStatus, response

    Examples:
      | deger |
      | 0     |
      | -1    |
      | 99999 |
