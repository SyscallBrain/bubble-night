# Historical fixtures

Synthetic profiles with no personal data, built from the models and serialization order of commits `035b152` (schema 1 with seenTips/fullAimGuide, before unlockAllLevels) and `2c34145` (earlier schema 1, long receipts). The SHA-256 matches the compact JSON of the original payload, in the historical field order and before any new defaults or migration were introduced. The tests import these files and also check that tampered copies are rejected.
