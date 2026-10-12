package pt.tiago.bubblenight.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import pt.tiago.bubblenight.core.model.Difficulty

class ProfileJsonTest {
    @Test fun historicalBackupsKeepTheirOriginalChecksumAndMigrateAfterVerification() {
        fun fixture(name: String) = javaClass.getResourceAsStream("/backups/$name")!!.use { it.readBytes() }
        assertEquals(Profile(), ProfileJson.prepareImport(fixture("schema1-before-test-mode.json")))
        val imported = ProfileJson.prepareImport(fixture("schema1-long-receipts.json"))
        assertEquals(MAX_TURN_RECEIPTS, imported.appliedTurnReceipts.size)
        assertEquals("session|45", imported.appliedTurnReceipts.first())
        assertEquals("session|300", imported.appliedTurnReceipts.last())
    }

    @Test fun legacyChecksumStillRejectsTampering() {
        val text = javaClass.getResourceAsStream("/backups/schema1-before-test-mode.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertThrows(IllegalArgumentException::class.java) { ProfileJson.prepareImport(text.replace("\"sound\": true", "\"sound\": false").toByteArray()) }
    }

    @Test fun versionTwoChecksumAcceptsReorderedKeysButRejectsUnsupportedFormats() {
        val json = kotlinx.serialization.json.Json
        val envelope = json.parseToJsonElement(ProfileJson.export(Profile()).toString(Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject
        val payload = envelope.getValue("profile") as kotlinx.serialization.json.JsonObject
        val reordered = kotlinx.serialization.json.JsonObject(envelope + ("profile" to kotlinx.serialization.json.JsonObject(payload.entries.reversed().associate { it.toPair() })))
        assertEquals(Profile(), ProfileJson.prepareImport(reordered.toString().toByteArray()))
        val unsupported = kotlinx.serialization.json.JsonObject(envelope + ("checksumVersion" to kotlinx.serialization.json.JsonPrimitive(99)))
        assertThrows(IllegalArgumentException::class.java) { ProfileJson.prepareImport(unsupported.toString().toByteArray()) }
    }
    @Test fun exportedBackupRoundTripsAndKeepsSettings() {
        val original = Profile(settings = Settings(
            sound = false, vibration = false, symbols = true, preciseAim = true,
            leftHanded = true, reducedMotion = true, aimAssist = false, lastDifficulty = Difficulty.HARD,
        ))
        val prepared = ProfileJson.prepareImport(ProfileJson.export(original))
        assertEquals(original, prepared)
    }

    @Test fun importRejectsOversizePayloadAndInvalidUtf8() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileJson.prepareImport(ByteArray(MAX_IMPORT_BYTES + 1))
        }
        assertThrows(Exception::class.java) { ProfileJson.prepareImport(byteArrayOf(0xc3.toByte(), 0x28)) }
    }

    @Test fun importRejectsChecksumTampering() {
        val exported = ProfileJson.export(Profile()).toString(Charsets.UTF_8)
        val tampered = exported.replace("\"revision\":0", "\"revision\":7").toByteArray(Charsets.UTF_8)
        assertThrows(Exception::class.java) { ProfileJson.prepareImport(tampered) }
    }

    @Test fun futureSchemaDoesNotMigrateToDefault() {
        assertThrows(UnsupportedProfileVersionException::class.java) {
            ProfileMigrations.migrate(Profile(schemaVersion = CURRENT_PROFILE_SCHEMA + 1))
        }
    }
}

