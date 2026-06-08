Feature: User Authentication API - AI Generated Test Suite
  # Bu test Karate DSL ile üretilmiştir – AI Test Generator v1.0
  # Kapsam: Login, Token Doğrulama, Logout senaryoları

  Background:
    * url baseUrl
    * configure ssl = true
    * configure connectTimeout = 10000
    * configure readTimeout = 30000
    * header Accept = 'application/json'
    * header Content-Type = 'application/json'

    # Test verisi
    * def validUser = { username: 'testuser@example.com', password: 'Test@123' }
    * def invalidUser = { username: 'wrong@example.com', password: 'WrongPass' }

  # ────────────────────────────────────────────────────
  # Başarılı login senaryosu
  # ────────────────────────────────────────────────────
  Scenario: POST /auth/login - Geçerli kimlik bilgileriyle başarılı giriş
    Given path '/auth/login'
    And request validUser
    When method POST
    Then status 200
    And match response.token == '#notnull'
    And match response.token == '#regex [A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+\\.?[A-Za-z0-9-_.+/=]*'
    And match response.expiresIn == '#number'
    And match response.user.email == validUser.username
    # Token'ı sonraki senaryolar için sakla
    * def authToken = response.token

  # ────────────────────────────────────────────────────
  # Hatalı şifre senaryosu
  # ────────────────────────────────────────────────────
  Scenario: POST /auth/login - Hatalı şifreyle 401 dönmeli
    Given path '/auth/login'
    And request invalidUser
    When method POST
    Then status 401
    And match response.error == '#notnull'
    And match response.message contains 'Invalid credentials'

  # ────────────────────────────────────────────────────
  # Validation hatası senaryosu
  # ────────────────────────────────────────────────────
  Scenario: POST /auth/login - Eksik alan 400 dönmeli
    Given path '/auth/login'
    And request { username: 'user@test.com' }
    When method POST
    Then status 400
    And match response.errors[0].field == 'password'

  # ────────────────────────────────────────────────────
  # Data-driven senaryo
  # ────────────────────────────────────────────────────
  Scenario Outline: POST /auth/login - Çeşitli geçersiz giriş denemeleri
    Given path '/auth/login'
    And request { username: '<username>', password: '<password>' }
    When method POST
    Then status <expectedStatus>

    Examples:
      | username           | password    | expectedStatus |
      |                    | Test@123    | 400            |
      | notanemail         | Test@123    | 400            |
      | user@test.com      |             | 400            |
      | nouser@test.com    | wrongpass   | 401            |

  # ────────────────────────────────────────────────────
  # Token doğrulama
  # ────────────────────────────────────────────────────
  Scenario: GET /auth/me - Geçerli token ile kullanıcı profili alınabilmeli
    # Önce login ol
    Given path '/auth/login'
    And request validUser
    When method POST
    Then status 200
    * def token = response.token

    # Profil sorgula
    Given path '/auth/me'
    And header Authorization = 'Bearer ' + token
    When method GET
    Then status 200
    And match response.email == validUser.username
    And match response.roles == '#[]'

  Scenario: GET /auth/me - Token olmadan 401 dönmeli
    Given path '/auth/me'
    When method GET
    Then status 401
