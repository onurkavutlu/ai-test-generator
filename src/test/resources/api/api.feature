Feature: API functional Test Suite (System Level)

  Background:
    * def targetUrl = karate.properties['baseUrl'] || 'http://localhost:8080'
    * url targetUrl
    * header Accept = 'application/json'
    * header Content-Type = 'application/json'

  Scenario: Verify CRUD operations of Mock Configuration API
    # 1. Register a new mock endpoint configuration
    Given path '/api/v1/mock-configs'
    And request { path: '/pet/44', method: 'GET', statusCode: 200, responseBody: '{"id": 44, "name": "Ates"}' }
    When method POST
    Then status 200
    And match response.path == '/pet/44'
    And match response.method == 'GET'
    And match response.statusCode == 200
    And match response.id == '#notnull'

    # 2. Get list of all registered mocks and check if ours exists
    Given path '/api/v1/mock-configs'
    When method GET
    Then status 200
    And match response == '#[]'
    And match response[*].path contains '/pet/44'

    # 3. Request the mock endpoint directly at the mock interceptor gateway
    Given path '/api/v1/mock/pet/44'
    When method GET
    Then status 200
    And match response.id == 44
    And match response.name == 'Ates'

    # 4. Clear all mock configurations
    Given path '/api/v1/mock-configs'
    When method DELETE
    Then status 200

    # 5. Verify the mock config list is now empty
    Given path '/api/v1/mock-configs'
    When method GET
    Then status 200
    And match response == '#[0]'
