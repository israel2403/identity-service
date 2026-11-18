Feature: Anonymous JSON:API user registration using JSON fixtures

  Background:
    Given a clean registration database and an anonymous HTTP client
    And registration fixture "valid"

  Scenario: Commit one account and return a credential-free resource
    When the client submits the registration fixture
    Then the response and database match the fixture
    And the committed password verifies against the fixture
    And the client remains anonymous

  Scenario Outline: Execute a registration contract fixture
    Given registration fixture "<fixture>"
    When the client submits the registration fixture
    Then the response and database match the fixture
    Examples:
      | fixture |
      | valid |
      | all-missing |
      | missing-fullname |
      | fullname-null |
      | fullname-number |
      | fullname-boolean |
      | fullname-array |
      | fullname-object |
      | missing-email |
      | email-null |
      | email-number |
      | email-boolean |
      | email-array |
      | email-object |
      | missing-password |
      | password-null |
      | password-number |
      | password-boolean |
      | password-array |
      | password-object |
      | fullname-invalid |
      | email-invalid |
      | password-invalid |
      | boundary-100-15 |
      | boundary-100-128 |
      | boundary-101-15 |
      | boundary-100-14 |
      | boundary-100-129 |
      | metadata |
      | empty-document |
      | bulk-data |
      | missing-attributes |
      | null-attributes |
      | array-attributes |
      | wrong-resource-type |
      | client-id |
      | roles |
      | hash-input |
      | relationships |
      | malformed |
      | duplicate-keys |
      | trailing-json |
      | wrong-content |
      | content-charset |
      | wrong-accept |
      | accept-zero |
      | accept-zero-wildcard |
      | accept-charset |
      | unsupported-accept-extension |
      | unsupported-extension |
      | accept-any |
      | accept-application |
      | accept-exact |
      | accept-alternative |
      | unknown-profile |
      | method |
      | oversized |
  
  Scenario: Reject a duplicate normalized email
    When the client submits the registration fixture
    Then the response and database match the fixture
    Given registration fixture "duplicate"
    When the client submits the registration fixture
    Then the response and database match the fixture

  Scenario: Concurrent duplicates have one winner
    When the client submits two fixture email variants concurrently
    Then the concurrent responses match the fixtures

  @unexpected-failure
  Scenario: Sanitize an unexpected application failure
    Given registration fixture "server-failure"
    And the registration service fails unexpectedly
    When the client submits the registration fixture
    Then the response and database match the fixture
