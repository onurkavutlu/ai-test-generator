Feature: LLM improvement report metadata

  Background:
    * def improvement =
      """
      {
        failedTest: 'CreatePetValidationTest',
        improvedTest: 'CreatePetValidationFixedTest',
        source: 'AUTO_FIX',
        tags: ['AUTO-FIX', 'LLM-GENERATED'],
        rootCause: 'The generated assertion expected only HTTP 400, but the contract can reject invalid create requests with 400 or 405.',
        changes: [
          'expanded accepted validation status codes',
          'kept missing-name negative coverage',
          'added invalid-status coverage'
        ],
        newCoverage: ['missing name validation', 'invalid status validation'],
        expectedImpact: 'Reduce false failures while keeping contract-level validation coverage.'
      }
      """

  Scenario: Improvement report explains why a test was changed
    * match improvement.source == 'AUTO_FIX'
    * match improvement.tags contains 'AUTO-FIX'
    * match improvement.failedTest == 'CreatePetValidationTest'
    * match improvement.improvedTest == 'CreatePetValidationFixedTest'
    * match improvement.rootCause contains '400 or 405'

  Scenario: Improvement report lists concrete changes and coverage
    * match improvement.changes contains 'expanded accepted validation status codes'
    * match improvement.newCoverage contains 'invalid status validation'
    * match improvement.expectedImpact contains 'Reduce false failures'
