Feature: Smoke Test Suite (Acceptance Level)

  Background:
    * def targetUrl = karate.properties['baseUrl'] || 'http://localhost:8080'
    * url targetUrl
    * header Accept = 'application/json'

  Scenario: Liveness Probe Verification
    Given path '/actuator/health/liveness'
    When method GET
    Then status 200
    And match response.status == 'UP'

  Scenario: Readiness Probe Verification
    Given path '/actuator/health/readiness'
    When method GET
    Then status 200
    And match response.status == 'UP'

  Scenario: Service Health Endpoint Verification
    Given path '/api/v1/tests/health'
    When method GET
    Then status 200
    And match response.status == 'UP'
