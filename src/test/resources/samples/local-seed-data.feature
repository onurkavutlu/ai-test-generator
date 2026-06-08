Feature: Local seed data contract

  Background:
    * def seedRequest =
      """
      {
        testType: 'BACKEND_API',
        framework: 'KARATE',
        status: 'GENERATED',
        scheduledRun: true,
        testCases: [
          {
            testName: 'GetPetByIdTest',
            fileName: 'GetPetByIdTest.feature',
            framework: 'KARATE',
            runStatus: 'PASSED',
            totalScenarios: 4,
            passedScenarios: 4,
            failedScenarios: 0
          },
          {
            testName: 'CreatePetValidationTest',
            fileName: 'CreatePetValidationTest.feature',
            framework: 'KARATE',
            runStatus: 'FAILED',
            totalScenarios: 3,
            passedScenarios: 2,
            failedScenarios: 1
          },
          {
            testName: 'PaymentAuthorizationContractTest',
            fileName: 'PaymentAuthorizationContractTest.feature',
            framework: 'KARATE',
            runStatus: 'PASSED',
            totalScenarios: 6,
            passedScenarios: 6,
            failedScenarios: 0
          },
          {
            testName: 'MoneyTransferLimitTest',
            fileName: 'MoneyTransferLimitTest.feature',
            framework: 'KARATE',
            runStatus: 'FAILED',
            totalScenarios: 5,
            passedScenarios: 4,
            failedScenarios: 1
          }
        ]
      }
      """

  Scenario: Backend API seed request contains runnable Karate feature files
    * match seedRequest.testType == 'BACKEND_API'
    * match seedRequest.framework == 'KARATE'
    * match seedRequest.testCases[*].fileName contains 'GetPetByIdTest.feature'
    * match seedRequest.testCases[*].fileName contains 'CreatePetValidationTest.feature'
    * match seedRequest.testCases[*].fileName contains 'PaymentAuthorizationContractTest.feature'
    * match seedRequest.testCases[*].fileName contains 'MoneyTransferLimitTest.feature'

  Scenario: Seed data keeps scenario counters consistent
    * def passedCase = seedRequest.testCases[0]
    * def failedCase = seedRequest.testCases[1]
    * def paymentCase = seedRequest.testCases[2]
    * def bankingCase = seedRequest.testCases[3]
    * match passedCase.totalScenarios == passedCase.passedScenarios + passedCase.failedScenarios
    * match failedCase.totalScenarios == failedCase.passedScenarios + failedCase.failedScenarios
    * match paymentCase.totalScenarios == paymentCase.passedScenarios + paymentCase.failedScenarios
    * match bankingCase.totalScenarios == bankingCase.passedScenarios + bankingCase.failedScenarios
    * match failedCase.runStatus == 'FAILED'
    * match bankingCase.runStatus == 'FAILED'
