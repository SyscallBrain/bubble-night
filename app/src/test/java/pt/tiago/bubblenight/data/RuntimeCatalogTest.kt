package pt.tiago.bubblenight.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Decodes the catalog exported into the APK assets with the same strict parser the app uses. */
class RuntimeCatalogTest {
    private val strict = Json { ignoreUnknownKeys = false }

    @Test
    fun exportedCatalogDecodesWithStrictParser() {
        val file = File("build/generated/bubbleAssets/catalog.json")
        assertEquals("exportGameAssets must run before the tests", true, file.isFile)
        val catalog = strict.decodeFromString<RuntimeCatalog>(file.readText(Charsets.UTF_8))
        assertEquals(catalog.expectedBaseCount, catalog.levels.size)
        assertEquals(catalog.expectedVariantCount, catalog.levels.sumOf { it.variants.size })
        assertEquals(catalog.levels.size, catalog.chapters.sumOf { it.lastOrdinal - it.firstOrdinal + 1 })
    }
}
