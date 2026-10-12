package pt.tiago.bubblenight.leveltool

import kotlinx.serialization.Serializable

@Serializable
internal data class ContentCatalog(
    val schemaVersion: Int,
    val engineVersion: Int,
    val contentVersion: String,
    val buildStage: BuildStage,
    val expectedBaseCount: Int,
    val expectedVariantCount: Int,
    val chapters: List<ContentChapter>,
    val levels: List<CatalogEntry>,
)

@Serializable
internal enum class BuildStage { slice, pilot, expanding, full }

@Serializable
internal data class ContentChapter(
    val orbitId: String,
    val firstOrdinal: Int,
    val lastOrdinal: Int,
    val titlePt: String,
)

@Serializable
internal data class CatalogEntry(
    val id: String,
    val ordinal: Int,
    val path: String,
    val revision: Int,
    val sha256: String,
)

@Serializable
internal data class SourceLevel(
    val schemaVersion: Int,
    val engineVersion: Int,
    val id: String,
    val ordinal: Int,
    val orbitId: String,
    val titlePt: String,
    val familyId: String,
    val learningTags: List<String>,
    val revision: Int,
    val authorNotes: String? = null,
    val variants: Map<String, SourceVariant>,
)

@Serializable
internal data class SourceVariant(
    val seedHex: String,
    val board: List<SourceCell>,
    val objective: SourceObjective,
    val maxShots: Int,
    val threeStarMaxShots: Int,
    val twoStarMaxShots: Int,
    val expectedMetrics: ExpectedMetrics,
)

@Serializable
internal data class SourceCell(val r: Int, val c: Int, val kind: String, val color: String? = null)

@Serializable
internal data class SourceObjective(val type: String, val total: Int? = null, val colors: List<String>? = null)

@Serializable
internal data class ExpectedMetrics(
    val familyId: String,
    val minBounces: Int,
    val maxInitialRow: Int? = null,
    val minInitialCells: Int? = null,
    val minAimWindowMilliDegrees: Int? = null,
    val authorRationale: String? = null,
)

@Serializable
internal data class Certificate(
    val schemaVersion: Int,
    val baseId: String,
    val difficulty: String,
    val levelRevision: Int,
    val levelSemanticHash: String,
    val engineVersion: Int,
    val seedHex: String,
    val initialStateHash: String,
    val steps: List<CertificateStep>,
    val finalStateHash: String,
    val shotsUsed: Int,
    val score: Long,
    val rescuedStars: Int,
    val outcome: String,
    val aimWindows: List<AimWindow> = emptyList(),
)

@Serializable
internal data class CertificateStep(
    val swapBefore: Boolean,
    val angleMilliDegrees: Int,
    val afterStateHash: String,
    val metrics: ShotMetrics,
)

@Serializable
internal data class CertificateCell(val r: Int, val c: Int)

@Serializable
internal data class ShotMetrics(
    val target: CertificateCell,
    val bounces: Int,
    val matchedCells: Int,
    val droppedCells: Int,
    val rescuedStars: Int,
    val testedAimWindowMilliDegrees: Int,
)

@Serializable
internal data class AimWindow(
    val stepIndex: Int,
    val lowerMilliDegrees: Int,
    val upperMilliDegrees: Int,
    val testedWidthMilliDegrees: Int,
)

@Serializable
internal data class OpeningBlueprintSet(
    val templateSetVersion: Int,
    val engineVersion: Int,
    val status: String,
    val levels: List<OpeningBlueprint>,
)

@Serializable
internal data class OpeningBlueprint(
    val ordinal: Int,
    val titlePt: String,
    val familyId: String,
    val learningTags: List<String>,
    val objective: SourceObjective,
    val minBounces: Int,
    val board: List<SourceCell>,
    val difficultyBoards: Map<String, List<SourceCell>> = emptyMap(),
)

@Serializable
internal data class GenerationRecord(
    val generatorVersion: Int,
    val seed: Long,
    val templatesSha256: String,
    val orbit: Int,
    val count: Int,
    val status: String,
)
