Feature: LLM generation audit metadata

  Background:
    * def generatedCases =
      """
      [
        {
          testName: 'GetPetByIdTest',
          source: 'LLM_GENERATED',
          tags: ['LOCAL-SEED', 'LLM-GENERATED'],
          actions: [
            '200 OK response validation added',
            '404 Not Found negative case added',
            'response id assertion added'
          ],
          createdArtifacts: ['GetPetByIdTest.feature']
        },
        {
          testName: 'LoginPageTest',
          source: 'LLM_GENERATED',
          tags: ['LOCAL-SEED', 'LLM-GENERATED'],
          actions: [
            'successful login scenario added',
            'invalid password scenario added',
            'dashboard visibility assertion added'
          ],
          createdArtifacts: ['LoginPageTest.java']
        },
        {
          testName: 'PaymentAuthorizationContractTest',
          source: 'LLM_GENERATED',
          tags: ['LOCAL-SEED', 'LLM-GENERATED', 'PAYMENT'],
          actions: [
            'successful TRY card authorization added',
            'insufficient limit business error added',
            'idempotency-key repeat behavior added',
            '3DS required branch added'
          ],
          createdArtifacts: ['PaymentAuthorizationContractTest.feature']
        },
        {
          testName: 'CheckoutHappyPathTest',
          source: 'LLM_GENERATED',
          tags: ['LOCAL-SEED', 'LLM-GENERATED', 'WEB-CHECKOUT'],
          actions: [
            'cart summary assertion added',
            'saved address selection added',
            'card payment completion added',
            'order confirmation number assertion added'
          ],
          createdArtifacts: ['CheckoutHappyPathTest.java']
        }
      ]
      """

  Scenario: Every generated test exposes what the LLM added
    * match each generatedCases contains { source: 'LLM_GENERATED' }
    * match each generatedCases[*].actions == '#[]'
    * match each generatedCases[*].createdArtifacts == '#[]'
    * match generatedCases[0].actions contains '404 Not Found negative case added'
    * match generatedCases[1].createdArtifacts contains 'LoginPageTest.java'
    * match generatedCases[2].actions contains 'idempotency-key repeat behavior added'
    * match generatedCases[3].createdArtifacts contains 'CheckoutHappyPathTest.java'

  Scenario: Generation tags are available for API and email reports
    * match generatedCases[0].tags contains 'LLM-GENERATED'
    * match generatedCases[0].tags contains 'LOCAL-SEED'
    * match generatedCases[1].tags contains 'LLM-GENERATED'
    * match generatedCases[2].tags contains 'PAYMENT'
    * match generatedCases[3].tags contains 'WEB-CHECKOUT'
