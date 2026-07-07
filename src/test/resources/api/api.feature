Feature: API functional Test Suite (System Level)

  Background:
    * def targetUrl = karate.properties['baseUrl'] || 'http://localhost:8080'
    * url targetUrl
    * header Accept = 'application/json'
    * header Content-Type = 'application/json'

  Scenario: Test generation request lifecycle with manual test case
    # 1. Create a generation request (autoRun=false; async LLM üretimi bu testin kapsamı dışında)
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
    And match response == '#[1]'
    And match response[0].testName == 'ManualSmokeTest'

    # 5. Tüm istekler listesinde bu request yer almalı
    Given path '/api/v1/tests'
    When method GET
    Then status 200
    And match response[*].id contains requestId

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
