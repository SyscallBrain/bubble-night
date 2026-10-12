package pt.tiago.bubblenight.leveltool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

/** Runs against the published catalog; the Gradle test working directory is the leveltool module. */
class ContentCommandsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val projectRoot: Path = Path.of("..").toAbsolutePath().normalize()
    private val catalog = projectRoot.resolve("content/catalog.json")
    private val certificates = projectRoot.resolve("content/certificates")

    @Test
    fun publishedCatalogPassesStrictValidation() {
        assertEquals(0, ContentCommands.validate(catalog, strict = true))
    }

    @Test
    fun everyPublishedCertificateReplaysWithTheEngine() {
        assertEquals(0, ContentCommands.verifyCertificates(catalog, certificates))
    }

    @Test
    fun tamperedStateHashIsRejected() {
        val copy = tmp.newFolder("certificates").toPath()
        certificates.toFile().copyRecursively(copy.toFile(), overwrite = true)
        val target = copy.resolve("level-0001/easy.json")
        val original = target.toFile().readText()
        val tampered = original.replaceFirst(Regex("\"afterStateHash\": \"[0-9a-f]{64}\""), "\"afterStateHash\": \"${"0".repeat(64)}\"")
        assertTrue("the test certificate must contain afterStateHash", tampered != original)
        target.toFile().writeText(tampered)
        try {
            ContentCommands.verifyCertificates(catalog, copy)
            fail("a tampered certificate was accepted")
        } catch (expected: CliFailure) {
            assertTrue(expected.message.orEmpty().contains("level-0001/easy"))
        }
    }
}
