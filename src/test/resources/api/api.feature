Feature: API functional Test Suite (System Level)

  Background:
    * def targetUrl = karate.properties['baseUrl'] || 'http://localhost:8080'
    * url targetUrl
    * header Accept = 'application/json'
    * header Content-Type = 'application/json'

  Scenario: Test generation request lifecycle with manual test case
    # 1. Create a generation request (autoRun=false yalnız otomatik koşumu kapatır;
    #    async üretim yine başlar ve senaryo sonunda terminal durumu beklenir)
    Given path '/api/v1/tests/generate'
    And param autoRun = false
    And request { testType: 'BACKEND_API', framework: 'KARATE', userStory: 'Sistem testi: manuel case akisi' }
    When method POST
    Then status 202
    And match response.requestId == '#notnull'
    * def requestId = response.requestId

    # 2. Request detayı sorgulanabilmeli
    Given path '/api/v1/tests/' + requestId
    When method GET
    Then status 200
    And match response.id == requestId
    And match response.framework == 'KARATE'

    # 3. Manuel test case ekle
    Given path '/api/v1/tests/' + requestId + '/cases'
    And request { testName: 'ManualSmokeTest', fileName: 'ManualSmokeTest.feature', testContent: 'Feature: manual', framework: 'KARATE' }
    When method POST
    Then status 200
    And match response.testName == 'ManualSmokeTest'
    And match response.id == '#notnull'

    # 4. Case listesinde görünmeli
    Given path '/api/v1/tests/' + requestId + '/cases'
    When method GET
    Then status 200
    And match response[*].testName contains 'ManualSmokeTest'

    # 5. Tüm istekler listesinde bu request yer almalı
    Given path '/api/v1/tests'
    When method GET
    Then status 200
    And match response[*].id contains requestId

    # 6. Spring test bağlamı kapanmadan async üretim bitmeli; aksi halde agent işi
    #    Surefire fork JVM'inde askıda kalır.
    * configure retry = { count: 100, interval: 100 }
    Given path '/api/v1/tests/' + requestId
    And retry until response.status == 'GENERATED' || response.status == 'FAILED'
    When method GET
    Then status 200
    And match response.status == '#? _ == "GENERATED" || _ == "FAILED"'

  Scenario: Incompatible framework is rejected with 400
    Given path '/api/v1/tests/generate'
    And request { testType: 'BACKEND_API', framework: 'SELENIUM', userStory: 'uyumsuz istek' }
    When method POST
    Then status 400
    And match response.error contains 'uyumsuz'

  Scenario: Unknown request id returns 404
    Given path '/api/v1/tests/00000000-0000-0000-0000-000000000000'
    When method GET
    Then status 404
