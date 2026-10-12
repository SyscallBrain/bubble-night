package pt.tiago.bubblenight.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

object ProfileJson {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        classDiscriminator = "type"
    }

    /** Store encoding. The repository validates each commit once before it reaches here. */
    fun encodeProfile(profile: Profile): ByteArray =
        json.encodeToString(Profile.serializer(), profile).toByteArray(Charsets.UTF_8)

    fun decodeProfile(bytes: ByteArray): Profile {
        val text = decodeUtf8(bytes)
        val root = json.parseToJsonElement(text).jsonObject
        val storedSchema = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("Missing profile schemaVersion")
        if (storedSchema > CURRENT_PROFILE_SCHEMA) throw UnsupportedProfileVersionException(storedSchema)
        val decoded = json.decodeFromString(Profile.serializer(), text)
        val migrated = ProfileMigrations.migrate(decoded)
        return ProfileValidator.requireValid(migrated)
    }

    fun export(profile: Profile): ByteArray = exportValidated(ProfileValidator.requireValid(canonicalize(profile)))

    /** Export of a profile that is already canonical and valid (the stored one). */
    internal fun exportValidated(canonical: Profile): ByteArray {
        val payload = json.encodeToString(Profile.serializer(), canonical)
        val envelope = ProfileBackupEnvelope(
            schemaVersion = canonical.schemaVersion,
            contentVersion = canonical.contentVersion,
            engineVersion = canonical.engineVersion,
            profile = canonical,
            sha256 = sha256(checksumPayload(json.parseToJsonElement(payload), 2)),
            checksumVersion = 2,
        )
        val bytes = json.encodeToString(ProfileBackupEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_IMPORT_BYTES) { "Profile exceeds export/import size limit" }
        return bytes
    }

    fun prepareImport(bytes: ByteArray): Profile {
        require(bytes.size <= MAX_IMPORT_BYTES) { "Import exceeds 2 MiB" }
        val text = decodeUtf8(bytes)
        val original = json.parseToJsonElement(text).jsonObject.getValue("profile").jsonObject
        val envelope = json.decodeFromString(ProfileBackupEnvelope.serializer(), text)
        require(envelope.schemaVersion == envelope.profile.schemaVersion) { "Envelope/profile schema mismatch" }
        require(envelope.contentVersion == envelope.profile.contentVersion) { "Envelope/profile content version mismatch" }
        require(envelope.engineVersion == envelope.profile.engineVersion) { "Envelope/profile engine version mismatch" }
        require(envelope.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 field" }
        val originalHash = sha256(checksumPayload(original, envelope.checksumVersion))
        // Historical importers also accepted reordered keys/sets after model canonicalization.
        val legacyHash = if (envelope.checksumVersion == 1)
            sha256(json.encodeToString(Profile.serializer(), canonicalize(envelope.profile)).toByteArray(Charsets.UTF_8)) else null
        fun matches(hash: String) = MessageDigest.isEqual(envelope.sha256.toByteArray(Charsets.US_ASCII), hash.toByteArray(Charsets.US_ASCII))
        require(matches(originalHash) || legacyHash?.let(::matches) == true) {
            "Backup checksum does not match"
        }
        return ProfileValidator.requireValid(ProfileMigrations.migrate(envelope.profile))
    }

    private fun checksumPayload(payload: JsonElement, version: Int): ByteArray {
        require(version in 1..2) { "Unsupported backup checksum version: $version" }
        fun ordered(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.toSortedMap().mapValues { ordered(it.value) })
            is JsonArray -> JsonArray(element.map(::ordered))
            else -> element
        }
        return (if (version == 2) ordered(payload) else payload).toString().toByteArray(Charsets.UTF_8)
    }

    fun canonicalize(profile: Profile): Profile = profile.copy(
        progressByDifficulty = profile.progressByDifficulty.toSortedMap(compareBy { it.name }).mapValues { (_, progress) ->
            progress.copy(
                completedVariantIds = progress.completedVariantIds.toSortedSet(),
                bestByVariantId = progress.bestByVariantId.toSortedMap(),
            )
        },
        statsByDifficulty = profile.statsByDifficulty.toSortedMap(compareBy { it.name }),
        appliedOutcomeReceipts = profile.appliedOutcomeReceipts.toList(),
        appliedTurnReceipts = profile.appliedTurnReceipts.toList(),
        achievements = profile.achievements.toSortedSet(),
        seenTips = profile.seenTips.toSortedSet(),
        zenFavorites = profile.zenFavorites.toSortedSet(),
        dailyResults = profile.dailyResults.toSortedMap(),
        freeSession = when (val free = profile.freeSession) {
            is FreeSession.Endless -> FreeSession.Endless(free.run.copy(orderedVariantIds = free.run.orderedVariantIds.toList()))
            else -> free
        },
    )

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
private const val MAX_PROFILE_BYTES = 8 * 1024 * 1024

object ProfileSerializer : Serializer<Profile> {
    override val defaultValue: Profile = Profile()

    override suspend fun readFrom(input: InputStream): Profile {
        // Ordinary IO failures propagate; only malformed data triggers backup recovery.
        val bytes = input.readProfileBytes(MAX_PROFILE_BYTES + 1)
        return try {
        if (bytes.size > MAX_PROFILE_BYTES) throw IllegalArgumentException("Stored profile is too large")
        ProfileJson.decodeProfile(bytes)
    } catch (error: Exception) {
        if (error is CorruptionException) throw error
        if (error is UnsupportedProfileVersionException) throw error
        throw CorruptionException("Bubble Night profile is corrupt or unsupported", error)
    }
    }

    override suspend fun writeTo(t: Profile, output: OutputStream) {
        output.write(ProfileJson.encodeProfile(t))
    }
}

/** Bounded read compatible with Android 26 (InputStream.readNBytes requires a newer API). */
internal fun InputStream.readProfileBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        if (count == 0) {
            val single = read()
            if (single < 0) break
            output.write(single)
        } else output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

