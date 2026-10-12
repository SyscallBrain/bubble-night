package pt.tiago.bubblenight.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CatalogBuildStage {
    @SerialName("slice") SLICE,
    @SerialName("pilot") PILOT,
    @SerialName("expanding") EXPANDING,
    @SerialName("full") FULL,
}

@Serializable
data class CatalogChapter(
    val orbitId: String,
    val firstOrdinal: Int,
    val lastOrdinal: Int,
    val titlePt: String,
)

@Serializable
data class CatalogLevelEntry(
    val id: String,
    val ordinal: Int,
    val path: String,
    val revision: Int,
    val sha256: String,
)

/** Source catalog index from content/catalog.json. */
@Serializable
data class CatalogManifest(
    val schemaVersion: Int,
    val engineVersion: Int,
    val contentVersion: String,
    val buildStage: CatalogBuildStage,
    val expectedBaseCount: Int,
    val expectedVariantCount: Int,
    val chapters: List<CatalogChapter>,
    val levels: List<CatalogLevelEntry>,
)

/** Packaged app index. Variant paths resolve to serialized [LevelDefinition] files. */
@Serializable
data class AssetCatalogManifest(
    val schemaVersion: Int,
    val engineVersion: Int,
    val contentVersion: String,
    val buildStage: CatalogBuildStage,
    val expectedBaseCount: Int,
    val expectedVariantCount: Int,
    val chapters: List<CatalogChapter>,
    val levels: List<AssetCatalogLevel>,
)

@Serializable
data class AssetCatalogLevel(
    val id: String,
    val ordinal: Int,
    val orbitId: String,
    val titlePt: String,
    val familyId: String,
    val learningTags: List<String>,
    val revision: Int,
    val variants: Map<String, String>,
)
