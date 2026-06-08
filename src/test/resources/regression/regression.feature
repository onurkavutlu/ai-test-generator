Feature: System Regression Test Suite

  Background:
    # Read baseUrl from system property, default to localhost if not provided
    * def targetUrl = karate.properties['baseUrl'] || 'http://localhost:8080'
    * url targetUrl
    * configure ssl = true
    * header Accept = 'application/json'

  Scenario: Verify Actuator Health - Liveness
    Given path '/actuator/health/liveness'
    When method GET
    Then status 200
    And match response.status == 'UP'

  Scenario: Verify Actuator Health - Readiness
    Given path '/actuator/health/readiness'
    When method GET
    Then status 200
    And match response.status == 'UP'

  Scenario: Verify Mock Configurations CRUD Availability
    Given path '/api/v1/mock-configs'
    When method GET
    Then status 200

  Scenario: Verify Test Generation Endpoint Health
    Given path '/api/v1/tests/health'
    When method GET
    Then status 200
    And match response.status == 'UP'
