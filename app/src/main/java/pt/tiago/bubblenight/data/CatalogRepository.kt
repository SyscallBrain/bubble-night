package pt.tiago.bubblenight.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pt.tiago.bubblenight.core.model.Difficulty
import pt.tiago.bubblenight.core.model.LevelDefinition
import java.security.MessageDigest

@Serializable
data class RuntimeCatalog(
    val schemaVersion: Int,
    val engineVersion: Int,
    val contentVersion: String,
    val buildStage: String,
    val expectedBaseCount: Int,
    val expectedVariantCount: Int,
    val chapters: List<RuntimeChapter>,
    val levels: List<RuntimeLevel>,
)

@Serializable
data class RuntimeChapter(
    val orbitId: String,
    val firstOrdinal: Int,
    val lastOrdinal: Int,
    val titlePt: String,
)

@Serializable
data class RuntimeLevel(
    val id: String,
    val ordinal: Int,
    val orbitId: String,
    val titlePt: String,
    val familyId: String,
    val learningTags: List<String>,
    val revision: Int,
    val variants: Map<String, String>,
)

@Serializable
private data class AssetManifest(
    val manifestVersion: Int,
    val schemaVersion: Int,
    val engineVersion: Int,
    val contentVersion: String,
    val buildStage: String,
    val catalogSha256: String,
    val files: List<AssetHash>,
)
@Serializable private data class AssetHash(val path: String, val sha256: String)

/** Reads only packaged assets. No filesystem paths supplied by profile/import are followed. */
class CatalogRepository(context: Context) {
    private val assets = context.applicationContext.assets
    private val json = Json { ignoreUnknownKeys = false }
    private val cache = java.util.concurrent.ConcurrentHashMap<String, LevelDefinition>()
    @Volatile private var manifest: AssetManifest? = null
    @Volatile private var catalog: RuntimeCatalog? = null

    suspend fun load(): RuntimeCatalog = withContext(Dispatchers.IO) {
        catalog ?: synchronized(this@CatalogRepository) {
            catalog ?: run {
                val evidence = json.decodeFromString<AssetManifest>(readAsset("manifest.json").decodeToString())
                check(evidence.manifestVersion == 1 && evidence.schemaVersion == 1 && evidence.engineVersion == 1) {
                    "Unsupported content version"
                }
                val bytes = readAsset("catalog.json")
                check(sha(bytes) == evidence.catalogSha256) { "Corrupted catalog" }
                val loaded = json.decodeFromString<RuntimeCatalog>(bytes.decodeToString())
                check(loaded.schemaVersion == 1 && loaded.engineVersion == 1)
                check(loaded.levels.size == loaded.expectedBaseCount && loaded.expectedVariantCount == loaded.levels.size * 3)
                check(loaded.levels.map { it.ordinal } == (1..loaded.levels.size).toList())
                check(loaded.levels.map { it.id }.distinct().size == loaded.levels.size)
                check(loaded.contentVersion == evidence.contentVersion)
                manifest = evidence
                catalog = loaded
                loaded
            }
        }
    }

    suspend fun definition(id: String, difficulty: Difficulty): LevelDefinition = withContext(Dispatchers.IO) {
        val source = load().levels.firstOrNull { it.id == id } ?: error("Unknown level: $id")
        val key = "$id/${difficulty.name.lowercase(java.util.Locale.ROOT)}"
        cache[key] ?: run {
            val path = source.variants.getValue(difficulty.name.lowercase(java.util.Locale.ROOT))
            check(path.matches(Regex("levels/level-[0-9]{4}/(easy|medium|hard)\\.json"))) { "Invalid level path" }
            val bytes = readAsset(path)
            val expected = manifest!!.files.firstOrNull { it.path == path }?.sha256 ?: error("Level has no hash")
            check(sha(bytes) == expected) { "Corrupted level: $id" }
            json.decodeFromString<LevelDefinition>(bytes.decodeToString()).also {
                check(it.baseId == id && it.revision == source.revision)
                cache[key] = it
                // A handful of active levels is enough; the catalog can grow without retaining all boards.
                if (cache.size > 24) cache.keys.firstOrNull { old -> old != key }?.let(cache::remove)
            }
        }
    }

    private fun readAsset(name: String): ByteArray = assets.open(name).use { input ->
        val bytes = input.readBytes()
        check(bytes.size <= 2 * 1024 * 1024) { "Asset too large" }
        bytes
    }

    companion object {
        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
