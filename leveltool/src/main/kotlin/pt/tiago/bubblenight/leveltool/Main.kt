package pt.tiago.bubblenight.leveltool

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = try {
        LevelToolCli().run(args.toList())
    } catch (failure: CliFailure) {
        System.err.println("ERROR: ${failure.message}")
        2
    } catch (failure: Exception) {
        System.err.println("ERROR: ${failure.message ?: failure::class.simpleName}")
        1
    }
    if (exitCode != 0) exitProcess(exitCode)
}

internal class CliFailure(message: String) : IllegalArgumentException(message)

private class LevelToolCli {
    fun run(args: List<String>): Int {
        if (args.isEmpty() || args.first() in setOf("help", "--help", "-h")) {
            printUsage()
            return 0
        }
        val command = args.first()
        val options = Options(args.drop(1))
        return when (command) {
            "validate" -> {
                options.allow("catalog", "strict")
                ContentCommands.validate(options.path("catalog", "content/catalog.json"), options.flag("strict"))
            }
            "report" -> {
                options.allow("catalog", "output")
                ContentCommands.report(options.path("catalog", "content/catalog.json"), options.optionalPath("output"))
            }
            "export-assets" -> {
                options.allow("catalog", "output")
                ContentCommands.exportAssets(
                    options.path("catalog", "content/catalog.json"),
                    options.path("output", "app/build/generated/bubbleAssets"),
                )
            }
            "solve" -> {
                options.allow("catalog", "output", "resume", "beam", "max-expanded", "timeout-ms", "jobs")
                ContentCommands.solve(
                    options.path("catalog", "content/catalog.json"),
                    options.path("output", "content/certificates"),
                    options.flag("resume"),
                    options.optionalInt("beam", min = 1, max = 4096) ?: 128,
                    options.optionalInt("max-expanded", min = 1, max = 5_000_000) ?: 100_000,
                    options.optionalLong("timeout-ms", min = 1_000L, max = 3_600_000L) ?: 30_000L,
                    options.optionalInt("jobs", min = 1, max = 16) ?: 1,
                )
            }
            "verify-certificates" -> {
                options.allow("catalog", "certificates")
                ContentCommands.verifyCertificates(
                    options.path("catalog", "content/catalog.json"),
                    options.path("certificates", "content/certificates"),
                )
            }
            "rebalance" -> {
                options.allow("catalog", "certificates", "content-version", "orbit")
                ContentCommands.rebalance(
                    options.path("catalog", "content/catalog.json"),
                    options.path("certificates", "content/certificates"),
                    options.optionalString("content-version"),
                    options.optionalOrbits("orbit", min = 1),
                )
            }
            "generate" -> {
                options.allow("orbit", "count", "seed", "output")
                ContentCommands.generate(
                    options.int("orbit", min = 1, max = 12),
                    options.int("count", min = 1, max = 50),
                    options.long("seed"),
                    options.path("output"),
                )
            }
            "extend" -> {
                options.allow("base", "orbit", "count", "seed", "output", "retry")
                ContentCommands.extend(
                    options.path("base", "content/catalog.json"),
                    options.int("orbit", min = 2, max = 12),
                    options.int("count", min = 1, max = 50),
                    options.long("seed"),
                    options.path("output"),
                    options.optionalPath("retry")?.let { parseRetries(it) } ?: emptyMap(),
                )
            }
            "regenerate" -> {
                options.allow("catalog", "orbit", "seed", "output", "retry")
                ContentCommands.regenerate(
                    options.path("catalog", "content/catalog.json"),
                    options.optionalOrbits("orbit", min = 2) ?: throw CliFailure("Missing --orbit <2..12>"),
                    options.long("seed"),
                    options.path("output"),
                    options.optionalPath("retry")?.let { parseRetries(it) } ?: emptyMap(),
                )
            }
            "promote" -> {
                options.allow("candidates", "catalog", "write", "replace-orbit")
                ContentCommands.promote(
                    options.path("candidates"),
                    options.path("catalog", "content/catalog.json"),
                    options.flag("write"),
                    options.optionalOrbits("replace-orbit", min = 2) ?: emptySet(),
                )
            }
            else -> throw CliFailure("Unknown command: $command")
        }
    }

    private fun printUsage() {
        println(
            """Bubble Night leveltool
              |  validate --catalog <path> [--strict]
              |  solve --catalog <path> --output <dir> [--resume] [--jobs <1..16>]
              |  verify-certificates --catalog <path>
              |  rebalance --catalog <path> [--certificates <dir>] [--content-version <v>] [--orbit <orbits>]
              |  report --catalog <path> [--output <path>]
              |  export-assets --catalog <path> [--output <dir>]
              |  generate --orbit <1..12> --count <n> --seed <long> --output <dir>
              |  promote --candidates <dir> --catalog <path> [--write] [--replace-orbit <orbits>]
              |  regenerate --catalog <path> --orbit <orbits> --seed <long> --output <dir> [--retry <file>]
              |  extend --base <catalog> --orbit <2..12> --count <1..50> --seed <long> --output <dir>
              |
              |<orbits>: "3", "3,5" or "3-12".
              |Default export: app/build/generated/bubbleAssets
              |""".trimMargin(),
        )
    }
}

/** Parses "52=1,60=2" into redraw counts per ordinal, read from a file to keep the CLI simple. */
private fun parseRetries(path: java.nio.file.Path): Map<Int, Int> =
    java.nio.file.Files.readString(path).split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.associate { entry ->
        val (ordinal, redraw) = entry.split('=').map { it.trim().toInt() }
        ordinal to redraw
    }

private class Options(tokens: List<String>) {
    private val values = linkedMapOf<String, String?>()

    init {
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (!token.startsWith("--")) throw CliFailure("Unexpected argument: $token")
            val key = token.removePrefix("--")
            if (key in values) throw CliFailure("Repeated option: --$key")
            val next = tokens.getOrNull(index + 1)
            if (next == null || next.startsWith("--")) {
                values[key] = null
            } else {
                values[key] = next
                index++
            }
            index++
        }
    }

    fun allow(vararg allowed: String) {
        val unknown = values.keys - allowed.toSet()
        if (unknown.isNotEmpty()) throw CliFailure("Unknown option(s): ${unknown.sorted().joinToString { "--$it" }}")
    }

    fun flag(name: String): Boolean {
        if (name !in values) return false
        if (values[name] != null) throw CliFailure("--$name does not take a value")
        return true
    }

    fun path(name: String, default: String? = null): java.nio.file.Path {
        val value = if (name in values) values[name] ?: throw CliFailure("--$name requires a value")
        else default ?: throw CliFailure("Missing --$name")
        return java.nio.file.Path.of(value)
    }

    fun optionalPath(name: String): java.nio.file.Path? {
        if (name !in values) return null
        return java.nio.file.Path.of(values[name] ?: throw CliFailure("--$name requires a value"))
    }

    fun optionalString(name: String): String? {
        if (name !in values) return null
        return values[name] ?: throw CliFailure("--$name requires a value")
    }

    fun int(name: String, min: Int, max: Int): Int {
        val value = values[name] ?: throw CliFailure("Missing --$name <integer>")
        val parsed = value.toIntOrNull() ?: throw CliFailure("--$name must be an integer")
        if (parsed !in min..max) throw CliFailure("--$name must be between $min and $max")
        return parsed
    }

    /** Orbit list such as "3", "3,5" or "3-12", each within [min]..12. */
    fun optionalOrbits(name: String, min: Int): Set<Int>? {
        if (name !in values) return null
        val value = values[name] ?: throw CliFailure("--$name requires a value")
        val orbits = value.split(',').flatMap { part ->
            val bounds = part.trim().split('-').map { it.trim().toIntOrNull() ?: throw CliFailure("invalid --$name: $value") }
            when (bounds.size) {
                1 -> listOf(bounds[0])
                2 -> (bounds[0]..bounds[1]).toList()
                else -> throw CliFailure("invalid --$name: $value")
            }
        }.toSortedSet()
        if (orbits.isEmpty() || orbits.any { it !in min..12 }) throw CliFailure("--$name must be between $min and 12")
        return orbits
    }

    fun optionalInt(name: String, min: Int, max: Int): Int? {
        if (name !in values) return null
        return int(name, min, max)
    }

    fun optionalLong(name: String, min: Long, max: Long): Long? {
        if (name !in values) return null
        val value = values[name] ?: throw CliFailure("--$name requires a value")
        val parsed = value.toLongOrNull() ?: throw CliFailure("--$name must be a Long integer")
        if (parsed !in min..max) throw CliFailure("--$name must be between $min and $max")
        return parsed
    }

    fun long(name: String): Long {
        val value = values[name] ?: throw CliFailure("Missing --$name <integer>")
        return value.toLongOrNull() ?: throw CliFailure("--$name must be a Long integer")
    }
}
