package pt.tiago.bubblenight.leveltool

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pt.tiago.bubblenight.core.geometry.HexGrid
import pt.tiago.bubblenight.core.model.Board
import pt.tiago.bubblenight.core.model.Bubble
import pt.tiago.bubblenight.core.model.Cell
import pt.tiago.bubblenight.core.model.CellBubble
import pt.tiago.bubblenight.core.model.LevelDefinition
import pt.tiago.bubblenight.core.model.Objective
import pt.tiago.bubblenight.core.model.Difficulty
import pt.tiago.bubblenight.core.model.GameMode
import pt.tiago.bubblenight.core.model.Outcome
import pt.tiago.bubblenight.core.model.TurnEvent
import pt.tiago.bubblenight.core.rules.GameEngine
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal object ContentCommands {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }
    private val colorIds = listOf("blue", "purple", "green", "orange", "pink", "cyan")
        .withIndex().associate { it.value to it.index }
    private val stageCounts = mapOf(BuildStage.slice to 10, BuildStage.pilot to 50, BuildStage.full to 600)
    /** Portuguese chapter titles, stored as `titlePt`; the app ships its own translations. */
    private val orbitNames = listOf(
        "Primeiro Céu", "Vale Lunar", "Jardim de Estrelas", "Cintura de Asteroides",
        "Aurora Violeta", "Mar de Órbitas", "Ponte Celeste", "Ecos da Lua",
        "Nebulosa Azul", "Horizonte Distante", "Coroa Boreal", "Última Constelação",
    )

    fun validate(catalogPath: Path, strict: Boolean): Int {
        val catalogFile = catalogPath.toAbsolutePath().normalize()
        val catalog = read<ContentCatalog>(catalogFile)
        val projectRoot = contentRoot(catalogFile)
        val problems = validateCatalog(catalog, catalogFile, projectRoot)
        if (problems.isNotEmpty()) throw CliFailure(problems.joinToString("\n", prefix = "Validation failed:\n  - "))
        println("Catalog ${catalog.contentVersion}: ${catalog.levels.size} base levels / ${catalog.expectedVariantCount} declared variants (${catalog.buildStage}).")
        println("Structure, source hashes and static semantics are valid; engineVersion=${catalog.engineVersion}.")
        if (strict) {
            println("Strict mode: no fixture or out-of-campaign entry was counted.")
            println("Note: certificates were not replay-verified in this run.")
        }
        return 0
    }

    fun report(catalogPath: Path, output: Path?): Int {
        val catalogFile = catalogPath.toAbsolutePath().normalize()
        val catalog = read<ContentCatalog>(catalogFile)
        val problems = validateCatalog(catalog, catalogFile, contentRoot(catalogFile))
        if (problems.isNotEmpty()) throw CliFailure(problems.joinToString("\n", prefix = "Report refused; invalid catalog:\n  - "))
        val levels = catalog.levels.map { entry -> read<SourceLevel>(resolveEntry(contentRoot(catalogFile), entry.path)) }
        val report = ContentReport(
            schemaVersion = 1,
            contentVersion = catalog.contentVersion,
            engineVersion = catalog.engineVersion,
            buildStage = catalog.buildStage.name,
            baseCount = levels.size,
            variantCount = levels.size * 3,
            familyCounts = levels.groupingBy { it.familyId }.eachCount().toSortedMap(),
            orbitCounts = levels.groupingBy { it.orbitId }.eachCount().toSortedMap(),
            difficultyColorCounts = listOf("easy", "medium", "hard").associateWith { difficulty ->
                levels.mapNotNull { level -> level.variants[difficulty]?.board?.mapNotNull { it.color }?.distinct()?.size }
                    .groupingBy { it }.eachCount().toSortedMap()
            },
            proofStatus = "not-run-by-report-command",
            playtestStatus = "not-recorded",
        )
        val encoded = json.encodeToString(report) + "\n"
        if (output == null) print(encoded) else writeIfChanged(output.toAbsolutePath().normalize(), encoded.toByteArray())
        return 0
    }

    fun solve(catalogPath: Path, output: Path, resume: Boolean, beam: Int, maxExpanded: Int, timeoutMillis: Long, jobs: Int = 1): Int {
        val catalogFile = catalogPath.toAbsolutePath().normalize()
        val catalog = read<ContentCatalog>(catalogFile)
        val projectRoot = contentRoot(catalogFile)
        val problems = validateCatalog(catalog, catalogFile, projectRoot)
        if (problems.isNotEmpty()) throw CliFailure(problems.joinToString("\n", prefix = "Solver refused an invalid catalog:\n  - "))
        val certificatesRoot = output.toAbsolutePath().normalize()
        val tasks = catalog.levels.sortedBy { it.ordinal }.flatMap { entry ->
            val source = read<SourceLevel>(resolveEntry(projectRoot, entry.path))
            listOf("easy", "medium", "hard").map { source to it }
        }
        // Variants are independent: each worker runs its own search and writes its own certificate.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(jobs)
        val results = try {
            tasks.map { (source, difficultyName) -> pool.submit<SolveRow> task@{
                val certificatePath = certificatesRoot.resolve(source.id).resolve("$difficultyName.json")
                if (resume && Files.isRegularFile(certificatePath)) {
                    val stale = verifyOne(source, difficultyName, catalog.engineVersion, certificatePath)
                    if (stale.isEmpty()) {
                        return@task SolveRow(source.id, difficultyName, "reused", 0, 0)
                    }
                }
                val variant = source.variants.getValue(difficultyName)
                val definition = toCoreLevel(source, difficultyName, variant)
                val difficulty = difficulty(difficultyName)
                val solution = CertificateSolver.solve(definition, difficulty,
                    width = beam, maxExpanded = maxExpanded, timeoutMillis = timeoutMillis,
                    minBounces = variant.expectedMetrics.minBounces,
                    requireSwap = "free-swap" in source.learningTags,
                    bankSide = when { "left-wall-bank" in source.learningTags -> -1; "right-wall-bank" in source.learningTags -> 1; else -> 0 },
                    minShots = if ("two-step-plan" in source.learningTags) 2 else 1,
                )
                if (solution == null) {
                    return@task SolveRow(source.id, difficultyName, "not-certified-timeout-or-not-found", 0, 0)
                }
                if (solution.actions.size > definition.threeStarMaxShots) {
                    return@task SolveRow(source.id, difficultyName, "not-certified-solution-exceeds-three-star-budget", solution.actions.size, solution.elapsedMillis)
                }
                val certificate = makeCertificate(source, difficultyName, catalog.engineVersion, definition, solution)
                writeIfChanged(certificatePath, (json.encodeToString(certificate) + "\n").toByteArray())
                val verification = verifyOne(source, difficultyName, catalog.engineVersion, certificatePath)
                if (verification.isNotEmpty()) throw CliFailure("Certificate created but verification replay failed for ${source.id}/$difficultyName: ${verification.joinToString()}")
                SolveRow(source.id, difficultyName, "certified-and-replayed", solution.actions.size, solution.elapsedMillis)
            } }.map { future ->
                try { future.get() } catch (error: java.util.concurrent.ExecutionException) { throw error.cause ?: error }
            }
        } finally {
            pool.shutdownNow()
        }
        val report = SolveReport(1, catalog.contentVersion, beam, maxExpanded, timeoutMillis, results)
        writeIfChanged(certificatesRoot.resolve("solve-report.json"), (json.encodeToString(report) + "\n").toByteArray())
        val certified = results.count { it.status == "certified-and-replayed" || it.status == "reused" }
        println("Solver finished: $certified/${results.size} variants certified/reused.")
        println("Report: ${certificatesRoot.resolve("solve-report.json")}")
        if (certified != results.size) println("Variants without a certificate stay uncertified; see the report.")
        return if (certified == results.size) 0 else 1
    }

    fun verifyCertificates(catalogPath: Path, certificatesPath: Path): Int {
        val catalogFile = catalogPath.toAbsolutePath().normalize()
        val catalog = read<ContentCatalog>(catalogFile)
        val projectRoot = contentRoot(catalogFile)
        val problems = validateCatalog(catalog, catalogFile, projectRoot).toMutableList()
        if (problems.isNotEmpty()) throw CliFailure(problems.joinToString("\n", prefix = "Verification refused; invalid catalog:\n  - "))
        var checked = 0
        for (entry in catalog.levels.sortedBy { it.ordinal }) {
            val source = read<SourceLevel>(resolveEntry(projectRoot, entry.path))
            for (difficultyName in listOf("easy", "medium", "hard")) {
                val path = certificatesPath.resolve(source.id).resolve("$difficultyName.json")
                val failures = verifyOne(source, difficultyName, catalog.engineVersion, path)
                if (failures.isNotEmpty()) problems += "${source.id}/$difficultyName: ${failures.joinToString(", ")}"
                else checked++
            }
        }
        if (problems.isNotEmpty()) throw CliFailure(problems.joinToString("\n", prefix = "Invalid certificates:\n  - "))
        println("Replay verified for $checked variants; all end in a win within the three-star limit.")
        return 0
    }

    private fun orbitOf(ordinal: Int) = (ordinal - 1) / 50 + 1

    /**
     * Shot budgets from the certified solution length S, per difficulty. Easy keeps a wide
     * margin; Hard asks for a near-optimal route. Short routes get a minimum budget.
     */
    internal fun budgetFor(difficultyName: String, solutionShots: Int): Triple<Int, Int, Int> {
        val (three, two, max, floor) = when (difficultyName) {
            "easy" -> listOf(3, 5, 8, 9)
            "medium" -> listOf(2, 3, 5, 7)
            else -> listOf(1, 2, 3, 5)
        }
        val maxShots = (solutionShots + max).coerceAtLeast(floor)
        val twoStars = (solutionShots + two).coerceAtMost(maxShots)
        val threeStars = (solutionShots + three).coerceAtMost(twoStars)
        return Triple(maxShots, twoStars, threeStars)
    }

    /**
     * Applies [budgetFor] to every variant and rebuilds each certificate by replaying its own
     * shots (same angles and swaps), so the proof stays the solver's while hashes and score
     * follow the current engine. A level whose budgets change gets a new revision.
     */
    fun rebalance(catalogPath: Path, certificatesPath: Path, contentVersion: String?, orbits: Set<Int>? = null): Int {
        val catalogFile = catalogPath.toAbsolutePath().normalize()
        val catalog = read<ContentCatalog>(catalogFile)
        val projectRoot = contentRoot(catalogFile)
        val before = validateCatalog(catalog, catalogFile, projectRoot)
        if (before.isNotEmpty()) throw CliFailure(before.joinToString("\n", prefix = "Rebalance refused; invalid catalog:\n  - "))
        var revised = 0
        val entries = catalog.levels.sortedBy { it.ordinal }.map { entry ->
            if (orbits != null && orbitOf(entry.ordinal) !in orbits) return@map entry
            val file = resolveEntry(projectRoot, entry.path)
            val source = read<SourceLevel>(file)
            val certificates = source.variants.keys.associateWith { name ->
                read<Certificate>(certificatesPath.resolve(source.id).resolve("$name.json"))
            }
            val variants = source.variants.mapValues { (name, variant) ->
                val (maxShots, two, three) = budgetFor(name, certificates.getValue(name).shotsUsed)
                variant.copy(maxShots = maxShots, twoStarMaxShots = two, threeStarMaxShots = three)
            }
            val next = if (variants == source.variants) source else {
                revised++
                source.copy(revision = source.revision + 1, variants = variants)
            }
            val bytes = (json.encodeToString(next) + "\n").toByteArray(Charsets.UTF_8)
            writeIfChanged(file, bytes)
            for ((name, variant) in next.variants) {
                val rebuilt = replayCertificate(next, name, variant, catalog.engineVersion, certificates.getValue(name))
                writeIfChanged(certificatesPath.resolve(next.id).resolve("$name.json"), (json.encodeToString(rebuilt) + "\n").toByteArray())
            }
            entry.copy(revision = next.revision, sha256 = sha256(bytes))
        }
        val updated = catalog.copy(contentVersion = contentVersion ?: catalog.contentVersion, levels = entries)
        writeIfChanged(catalogFile, (json.encodeToString(updated) + "\n").toByteArray(Charsets.UTF_8))
        println("Rebalance: $revised bases got a new revision; ${entries.size * 3} certificates rebuilt by replay.")
        return verifyCertificates(catalogFile, certificatesPath)
    }

    private fun replayCertificate(source: SourceLevel, difficultyName: String, variant: SourceVariant, engineVersion: Int, old: Certificate): Certificate {
        val definition = toCoreLevel(source, difficultyName, variant)
        var state = GameEngine.start(definition, difficulty(difficultyName), GameMode.CAMPAIGN, "certificate-${definition.variantId}")
        val initialHash = CertificateSolver.stateHash(state)
        val steps = old.steps.map { step ->
            if (step.swapBefore) state = GameEngine.swap(state)
            val result = GameEngine.shoot(state, step.angleMilliDegrees)
            if (!result.accepted) throw CliFailure("${source.id}/$difficultyName: replay rejected")
            state = result.state
            step.copy(afterStateHash = CertificateSolver.stateHash(state))
        }
        if (state.outcome != Outcome.WON) throw CliFailure("${source.id}/$difficultyName: replay does not end in a win")
        return old.copy(
            levelRevision = source.revision,
            levelSemanticHash = definition.semanticHash,
            engineVersion = engineVersion,
            initialStateHash = initialHash,
            steps = steps,
            finalStateHash = CertificateSolver.stateHash(state),
            shotsUsed = state.shotsUsed,
            score = state.score,
            rescuedStars = state.rescuedStars,
        )
    }

    private fun makeCertificate(
        source: SourceLevel,
        difficultyName: String,
        engineVersion: Int,
        definition: LevelDefinition,
        solution: SolverResult,
    ): Certificate {
        var state = GameEngine.start(definition, difficulty(difficultyName), GameMode.CAMPAIGN, "certificate-${definition.variantId}")
        val steps = solution.actions.mapIndexed { index, action ->
            if (action.swapBefore) state = GameEngine.swap(state)
            val result = GameEngine.shoot(state, action.angleMilliDegrees)
            check(result.accepted) { "Solver witness rejected during certificate assembly" }
            state = result.state
            CertificateStep(
                swapBefore = action.swapBefore,
                angleMilliDegrees = action.angleMilliDegrees,
                afterStateHash = CertificateSolver.stateHash(state),
                metrics = solution.metrics[index],
            )
        }
        check(state.outcome == Outcome.WON)
        return Certificate(
            schemaVersion = 1,
            baseId = source.id,
            difficulty = difficultyName,
            levelRevision = source.revision,
            levelSemanticHash = definition.semanticHash,
            engineVersion = engineVersion,
            seedHex = definition.seedHex,
            initialStateHash = solution.initialStateHash,
            steps = steps,
            finalStateHash = CertificateSolver.stateHash(state),
            shotsUsed = state.shotsUsed,
            score = state.score,
            rescuedStars = state.rescuedStars,
            outcome = "WON",
            aimWindows = solution.aimWindows,
        )
    }

    private fun verifyOne(source: SourceLevel, difficultyName: String, engineVersion: Int, certificatePath: Path): List<String> {
        if (!Files.isRegularFile(certificatePath)) return listOf("certificado em falta")
        val certificate = try { read<Certificate>(certificatePath) }
        catch (failure: Exception) { return listOf("invalid certificate JSON: ${failure.message}") }
        val errors = mutableListOf<String>()
        val variant = source.variants[difficultyName] ?: return listOf("variante fonte em falta")
        val definition = try { toCoreLevel(source, difficultyName, variant) }
        catch (failure: Exception) { return listOf("definition cannot be converted: ${failure.message}") }
        if (certificate.schemaVersion != 1) errors += "schemaVersion divergente"
        if (certificate.baseId != source.id || certificate.difficulty != difficultyName) errors += "ID/dificuldade divergentes"
        if (certificate.levelRevision != source.revision || certificate.levelSemanticHash != definition.semanticHash) errors += "content/revision changed"
        if (certificate.engineVersion != engineVersion) errors += "engineVersion divergente"
        if (certificate.seedHex != variant.seedHex || definition.seedHex != certificate.seedHex) errors += "seed divergente"
        if (certificate.outcome != "WON") errors += "outcome is not WON"
        if (certificate.steps.isEmpty() || certificate.steps.size != certificate.shotsUsed) errors += "contagem de disparos incoerente"
        if (certificate.aimWindows.size != certificate.steps.size) errors += "aimWindows does not cover every shot"
        val state = try { GameEngine.start(definition, difficulty(difficultyName), GameMode.CAMPAIGN, "verify-${definition.variantId}") }
        catch (failure: Exception) { return errors + "estado inicial rejeitado pelo core: ${failure.message}" }
        if (CertificateSolver.stateHash(state) != certificate.initialStateHash) errors += "initialStateHash divergente"
        var current = state
        for ((index, step) in certificate.steps.withIndex()) {
            if (current.outcome != Outcome.READY) { errors += "premature win/loss before step $index"; break }
            if (step.angleMilliDegrees !in -75_000..75_000) { errors += "invalid angle at step $index"; break }
            if (step.swapBefore) current = GameEngine.swap(current)
            val result = GameEngine.shoot(current, step.angleMilliDegrees)
            if (!result.accepted) { errors += "comando rejeitado no passo $index"; break }
            if (CertificateSolver.stateHash(result.state) != step.afterStateHash) errors += "afterStateHash divergente no passo $index"
            val target = result.preview?.target
            val matched = result.events.filterIsInstance<TurnEvent.Matched>().sumOf { it.cells.size }
            val dropped = result.events.filterIsInstance<TurnEvent.Dropped>().sumOf { it.cells.size }
            val rescued = result.events.filterIsInstance<TurnEvent.Rescued>().sumOf { it.count }
            val window = certificate.aimWindows.getOrNull(index)
            if (window == null) errors += "janela em falta no passo $index"
            else {
                if (window.stepIndex != index || window.lowerMilliDegrees > step.angleMilliDegrees || window.upperMilliDegrees < step.angleMilliDegrees ||
                    window.testedWidthMilliDegrees != window.upperMilliDegrees - window.lowerMilliDegrees)
                    errors += "intervalo angular incoerente no passo $index"
                val recorded = step.metrics.testedAimWindowMilliDegrees
                if (recorded != window.testedWidthMilliDegrees) errors += "largura angular diverge no passo $index"
                val minimumWidth = when (difficultyName) { "easy" -> 2000; "medium" -> 1000; else -> 500 }
                if (window.testedWidthMilliDegrees < minimumWidth) errors += "janela demasiado estreita no passo $index"
                val bounded = window.lowerMilliDegrees in -75000..75000 && window.upperMilliDegrees in -75000..75000
                if (!bounded) errors += "janela fora do intervalo permitido no passo $index"
                if (target != null && bounded && window.lowerMilliDegrees <= window.upperMilliDegrees) {
                    for (angle in window.lowerMilliDegrees..window.upperMilliDegrees) {
                        val probe = GameEngine.shoot(current, angle)
                        if (!probe.accepted || probe.state != result.state) {
                            errors += "intervalo angular inclui resultado diferente no passo $index"
                            break
                        }
                    }
                }
            }
            val actualMetrics = ShotMetrics(
                target = target?.let { CertificateCell(it.row, it.column) } ?: CertificateCell(-1, -1),
                bounces = result.preview?.segments?.count { it.bounceAfter } ?: -1,
                matchedCells = matched,
                droppedCells = dropped,
                rescuedStars = rescued,
                testedAimWindowMilliDegrees = step.metrics.testedAimWindowMilliDegrees,
            )
            if (actualMetrics.copy(testedAimWindowMilliDegrees = 0) != step.metrics.copy(testedAimWindowMilliDegrees = 0))
                errors += "replay metrics diverge at step $index"
            current = result.state
            if (current.outcome == Outcome.WON && index != certificate.steps.lastIndex)
                errors += "premature win with steps left over"
        }
        if (current.outcome != Outcome.WON) errors += "replay did not end in WON"
        if (CertificateSolver.stateHash(current) != certificate.finalStateHash) errors += "finalStateHash divergente"
        if (current.shotsUsed != certificate.shotsUsed || current.score != certificate.score || current.rescuedStars != certificate.rescuedStars)
            errors += "resultado final diverge"
        if (current.shotsUsed > definition.threeStarMaxShots) errors += "solution does not reach three stars"
        return errors.distinct()
    }

    private fun difficulty(value: String): Difficulty = when (value) {
        "easy" -> Difficulty.EASY
        "medium" -> Difficulty.MEDIUM
        "hard" -> Difficulty.HARD
        else -> throw CliFailure("Invalid difficulty: $value")
    }

    /** Generate an isolated candidate pack; publication always requires promote --write. */
    fun generate(orbit: Int, count: Int, seed: Long, output: Path): Int {
        if (orbit != 1 || count !in setOf(10, 50))
            throw CliFailure("Authored blueprints exist only for orbit 1, with 10 (slice) or 50 (pilot) levels. Other orbits need new blueprints; they will not be filled with clones.")
        val stage = if (count == 10) BuildStage.slice else BuildStage.pilot
        val templatePath = Path.of(if (count == 10) "content/templates/opening-slice.json" else "content/templates/orbit-01-blueprints.json")
        val templates = read<OpeningBlueprintSet>(templatePath)
        require(templates.engineVersion == GameEngine.ENGINE_VERSION)
        val destination = output.toAbsolutePath().normalize()
        if (Files.exists(destination)) Files.list(destination).use { paths ->
            if (paths.findAny().isPresent) throw CliFailure("Candidate destination is not empty: $destination; it will not be overwritten")
        }
        val rng = pt.tiago.bubblenight.core.random.SplitMix64(seed)
        val levels = templates.levels.sortedBy { it.ordinal }.take(count).map { blueprint ->
            val id = "level-${blueprint.ordinal.toString().padStart(4, '0')}"
            SourceLevel(
                schemaVersion = 1, engineVersion = templates.engineVersion, id = id,
                ordinal = blueprint.ordinal, orbitId = "orbit-01", titlePt = blueprint.titlePt,
                familyId = blueprint.familyId, learningTags = blueprint.learningTags, revision = 1,
                authorNotes = "Authored candidate; difficulty and teaching intent await replay and human review.",
                variants = listOf("easy", "medium", "hard").associateWith { name ->
                    val budget = when (name) { "easy" -> 14; "medium" -> 11; else -> 9 }
                    SourceVariant(
                        seedHex = java.lang.Long.toUnsignedString(rng.nextLong(), 16).padStart(16, '0'),
                        board = (blueprint.difficultyBoards[name] ?: blueprint.board).sortedWith(compareBy({ it.r }, { it.c })),
                        objective = blueprint.objective, maxShots = budget,
                        threeStarMaxShots = budget - 2, twoStarMaxShots = budget - 1,
                        expectedMetrics = ExpectedMetrics(blueprint.familyId, blueprint.minBounces),
                    )
                },
            )
        }
        val errors = mutableListOf<String>()
        levels.forEach { level -> level.variants.forEach { (name, variant) ->
            validateVariant(level, variant, name, errors::add)
            if (errors.isEmpty()) GameEngine.start(toCoreLevel(level, name, variant), difficulty(name), GameMode.CAMPAIGN, "generator")
        } }
        if (errors.isNotEmpty()) throw CliFailure(errors.joinToString("\n"))
        val entries = levels.map { level ->
            val relative = "content/levels/${level.orbitId}/${level.id}.json"
            val bytes = (json.encodeToString(level) + "\n").toByteArray(Charsets.UTF_8)
            writeIfChanged(destination.resolve(relative), bytes)
            CatalogEntry(level.id, level.ordinal, relative, level.revision, sha256(bytes))
        }
        val contentVersion = if (stage == BuildStage.slice) "0.1.0-slice" else "0.1.0-pilot"
        val catalog = ContentCatalog(1, templates.engineVersion, contentVersion, stage, count, count * 3,
            listOf(ContentChapter("orbit-01", 1, count, orbitNames.first())), entries)
        val catalogPath = destination.resolve("content/catalog.json")
        writeIfChanged(catalogPath, (json.encodeToString(catalog) + "\n").toByteArray(Charsets.UTF_8))
        writeIfChanged(destination.resolve("generation.json"), (json.encodeToString(GenerationRecord(
            1, seed, sha256(Files.readAllBytes(templatePath)), orbit, count, "candidate-not-certified",
        )) + "\n").toByteArray(Charsets.UTF_8))
        validate(catalogPath, strict = true)
        println("Candidates written to $catalogPath; run solve and verify-certificates before promoting.")
        return 0
    }

    /**
     * Adds one procedural orbit (2–12) to a pilot or expanding catalog. Existing levels and
     * certificates are copied unchanged; new candidates are checked by the rules before the
     * solver runs. Output stays in candidate form until promote --write.
     */
    fun extend(baseCatalogPath: Path, orbit: Int, count: Int, seed: Long, output: Path, retries: Map<Int, Int> = emptyMap()): Int {
        if (orbit !in 2..12) throw CliFailure("extend covers orbits 2–12; orbit 1 is hand-authored")
        if (count !in 1..50) throw CliFailure("--count must be between 1 and 50 (one orbit)")
        val baseFile = baseCatalogPath.toAbsolutePath().normalize()
        val base = read<ContentCatalog>(baseFile)
        val baseRoot = contentRoot(baseFile)
        val problems = validateCatalog(base, baseFile, baseRoot)
        if (problems.isNotEmpty()) throw CliFailure(problems.joinToString("\n", prefix = "Invalid base catalog:\n  - "))
        if (base.buildStage != BuildStage.pilot && base.buildStage != BuildStage.expanding)
            throw CliFailure("extend starts from a pilot or expanding catalog")
        val firstNew = base.expectedBaseCount + 1
        if (firstNew != (orbit - 1) * 50 + 1) throw CliFailure("orbit $orbit must start at level ${(orbit - 1) * 50 + 1}")
        val destination = output.toAbsolutePath().normalize()
        if (Files.exists(destination)) Files.list(destination).use { paths ->
            if (paths.findAny().isPresent) throw CliFailure("Candidate destination is not empty: $destination; it will not be overwritten")
        }
        for (entry in base.levels) {
            writeIfChanged(destination.resolve(entry.path), Files.readAllBytes(resolveEntry(baseRoot, entry.path)))
            val certs = baseRoot.resolve("content/certificates/${entry.id}")
            if (Files.isDirectory(certs)) Files.list(certs).use { files ->
                files.forEach { file -> writeIfChanged(destination.resolve("content/certificates/${entry.id}/${file.fileName}"), Files.readAllBytes(file)) }
            }
        }
        val familiesFor = ProceduralLevels.familiesFor(orbit)
        val accepted = mutableListOf<SourceLevel>()
        val seen = HashSet<String>()
        for (index in 0 until count) {
            // Each base has its own seed, so redrawing one base does not move the others.
            val ordinal = firstNew + index
            val rng = pt.tiago.bubblenight.core.random.SplitMix64(ProceduralLevels.levelSeed(seed, ordinal, retries[ordinal] ?: 0))
            var attempts = 0
            while (true) {
                attempts++
                if (attempts > 500) throw CliFailure("level $ordinal: no valid variant in 500 attempts")
                val level = ProceduralLevels.level(ordinal, orbit, familiesFor[index % familiesFor.size], rng)
                val errors = mutableListOf<String>()
                level.variants.forEach { (name, variant) -> validateVariant(level, variant, name, errors::add) }
                if (errors.isNotEmpty()) continue
                val key = level.variants.entries.joinToString("|") { (name, variant) ->
                    name + variant.board.joinToString(",") { "${it.r}:${it.c}:${it.kind}:${it.color}" }
                }
                if (!seen.add(key)) continue
                accepted += level
                break
            }
        }
        val newEntries = accepted.map { level ->
            val relative = "content/levels/${level.orbitId}/${level.id}.json"
            val bytes = (json.encodeToString(level) + "\n").toByteArray(Charsets.UTF_8)
            writeIfChanged(destination.resolve(relative), bytes)
            CatalogEntry(level.id, level.ordinal, relative, level.revision, sha256(bytes))
        }
        val total = base.expectedBaseCount + count
        val chapters = base.chapters + ContentChapter("orbit-%02d".format(orbit), firstNew, total, orbitNames[orbit - 1])
        val catalog = ContentCatalog(1, base.engineVersion, "0.1.0-expanding", BuildStage.expanding, total, total * 3, chapters,
            base.levels + newEntries)
        val catalogPath = destination.resolve("content/catalog.json")
        writeIfChanged(catalogPath, (json.encodeToString(catalog) + "\n").toByteArray(Charsets.UTF_8))
        validate(catalogPath, strict = true)
        println("Orbit $orbit: ${accepted.size} candidate bases; catalog has $total bases at $catalogPath")
        return 0
    }

    /**
     * Candidate pack where one published orbit (2–12) is replaced by new procedural bases with
     * the LARGE board profile. Every other level and certificate is copied unchanged; replaced
     * bases keep their id and ordinal and get the next revision. Promote with --replace-orbit.
     */
    fun regenerate(catalogPath: Path, orbits: Set<Int>, seed: Long, output: Path, retries: Map<Int, Int> = emptyMap()): Int {
        if (orbits.isEmpty() || orbits.any { it !in 2..12 }) throw CliFailure("regenerate covers orbits 2–12; orbit 1 is hand-authored")
        val baseFile = catalogPath.toAbsolutePath().normalize()
        val base = read<ContentCatalog>(baseFile)
        val baseRoot = contentRoot(baseFile)
        val problems = validateCatalog(base, baseFile, baseRoot)
        if (problems.isNotEmpty()) throw CliFailure(problems.joinToString("\n", prefix = "Invalid base catalog:\n  - "))
        for (orbit in orbits) if (base.levels.none { orbitOf(it.ordinal) == orbit }) throw CliFailure("orbit $orbit is not published")
        val destination = output.toAbsolutePath().normalize()
        if (Files.exists(destination)) Files.list(destination).use { paths ->
            if (paths.findAny().isPresent) throw CliFailure("Candidate destination is not empty: $destination; it will not be overwritten")
        }
        val seen = HashSet<String>()
        val entries = base.levels.sortedBy { it.ordinal }.map { entry ->
            val orbit = orbitOf(entry.ordinal)
            if (orbit !in orbits) {
                writeIfChanged(destination.resolve(entry.path), Files.readAllBytes(resolveEntry(baseRoot, entry.path)))
                val certs = baseRoot.resolve("content/certificates/${entry.id}")
                if (Files.isDirectory(certs)) Files.list(certs).use { files ->
                    files.forEach { file -> writeIfChanged(destination.resolve("content/certificates/${entry.id}/${file.fileName}"), Files.readAllBytes(file)) }
                }
                return@map entry
            }
            val rng = pt.tiago.bubblenight.core.random.SplitMix64(ProceduralLevels.levelSeed(seed, entry.ordinal, retries[entry.ordinal] ?: 0))
            var attempts = 0
            val level = generateSequence {
                attempts++
                if (attempts > 500) throw CliFailure("level ${entry.ordinal}: no valid variant in 500 attempts")
                val families = ProceduralLevels.familiesFor(orbit)
                ProceduralLevels.level(entry.ordinal, orbit, families[(entry.ordinal - 1) % 50 % families.size], rng,
                    ProceduralLevels.LARGE, revision = entry.revision + 1)
            }.first { level ->
                val errors = mutableListOf<String>()
                level.variants.forEach { (name, variant) -> validateVariant(level, variant, name, errors::add) }
                errors.isEmpty() && seen.add(level.variants.entries.joinToString("|") { (name, variant) ->
                    name + variant.board.joinToString(",") { "${it.r}:${it.c}:${it.kind}:${it.color}" }
                })
            }
            val bytes = (json.encodeToString(level) + "\n").toByteArray(Charsets.UTF_8)
            writeIfChanged(destination.resolve(entry.path), bytes)
            CatalogEntry(level.id, level.ordinal, entry.path, level.revision, sha256(bytes))
        }
        val catalog = base.copy(levels = entries)
        val candidateCatalog = destination.resolve("content/catalog.json")
        writeIfChanged(candidateCatalog, (json.encodeToString(catalog) + "\n").toByteArray(Charsets.UTF_8))
        validate(candidateCatalog, strict = true)
        val list = orbits.sorted().joinToString(",")
        println("Orbits $list regenerated (large boards) at $candidateCatalog; run solve, rebalance and promote --replace-orbit $list.")
        return 0
    }

    fun promote(candidates: Path, catalogPath: Path, write: Boolean, replaceOrbits: Set<Int> = emptySet()): Int {
        val pack = candidates.toAbsolutePath().normalize()
        val candidateCatalog = pack.resolve("content/catalog.json")
        val catalog = read<ContentCatalog>(candidateCatalog)
        validate(candidateCatalog, strict = true)
        verifyCertificates(candidateCatalog, pack.resolve("content/certificates"))
        val targetCatalog = catalogPath.toAbsolutePath().normalize()
        val targetRoot = contentRoot(targetCatalog)
        val staged = linkedMapOf<Path, ByteArray>()
        if (Files.isRegularFile(targetCatalog)) {
            val existing = read<ContentCatalog>(targetCatalog)
            if (!catalog.levels.map { it.id }.containsAll(existing.levels.map { it.id }))
                throw CliFailure("Promotion refused: the candidate would remove published bases")
        }
        for (entry in catalog.levels) {
            val sourcePath = resolveEntry(pack, entry.path)
            val level = read<SourceLevel>(sourcePath)
            val target = resolveEntry(targetRoot, entry.path)
            val bytes = Files.readAllBytes(sourcePath)
            if (Files.isRegularFile(target) && !Files.readAllBytes(target).contentEquals(bytes)) {
                val replaceable = orbitOf(entry.ordinal) in replaceOrbits &&
                    level.revision > read<SourceLevel>(target).revision
                if (!replaceable) throw CliFailure("Promotion refused: an already published level differs (${entry.id}); revisions need an explicit process")
            }
            staged[target] = bytes
            for ((name, variant) in level.variants.toSortedMap()) {
                println("${entry.id}/$name semantic=${semanticHash(level, name, variant)} clone=${sha256(fingerprint(variant, true).toByteArray())}")
                val certRelative = "content/certificates/${entry.id}/$name.json"
                staged[targetRoot.resolve(certRelative)] = Files.readAllBytes(pack.resolve(certRelative))
            }
        }
        if (!write) {
            println("Dry run: ${catalog.levels.size} bases checked; nothing written. Use --write to promote.")
            return 0
        }
        staged.forEach { (path, bytes) -> writeIfChanged(path, bytes) }
        // Publish the index last, so partial IO failures cannot expose missing levels.
        writeIfChanged(targetCatalog, Files.readAllBytes(candidateCatalog))
        println("Promoted ${catalog.expectedBaseCount} bases / ${catalog.expectedVariantCount} verified variants to $targetCatalog")
        return 0
    }

    fun exportAssets(catalogPath: Path, output: Path): Int {
        val catalogFile = catalogPath.toAbsolutePath().normalize()
        val catalog = read<ContentCatalog>(catalogFile)
        val projectRoot = contentRoot(catalogFile)
        val problems = validateCatalog(catalog, catalogFile, projectRoot)
        if (problems.isNotEmpty()) throw CliFailure(problems.joinToString("\n", prefix = "Export refused; invalid catalog:\n  - "))

        verifyCertificates(catalogFile, catalogFile.parent.resolve("certificates"))
        val destination = output.toAbsolutePath().normalize()
        val assetEntries = mutableListOf<pt.tiago.bubblenight.core.model.AssetCatalogLevel>()
        val generated = linkedMapOf<Path, ByteArray>()
        for (entry in catalog.levels.sortedBy { it.ordinal }) {
            val source = read<SourceLevel>(resolveEntry(projectRoot, entry.path))
            val paths = linkedMapOf<String, String>()
            for (difficulty in listOf("easy", "medium", "hard")) {
                val variant = source.variants.getValue(difficulty)
                val definition = toCoreLevel(source, difficulty, variant)
                val relativePath = "levels/${source.id}/$difficulty.json"
                paths[difficulty] = relativePath
                generated[destination.resolve(relativePath)] = (json.encodeToString(definition) + "\n").toByteArray()
            }
            assetEntries += pt.tiago.bubblenight.core.model.AssetCatalogLevel(
                id = source.id,
                ordinal = source.ordinal,
                orbitId = source.orbitId,
                titlePt = source.titlePt,
                familyId = source.familyId,
                learningTags = source.learningTags,
                revision = source.revision,
                variants = paths,
            )
        }
        val assetCatalog = pt.tiago.bubblenight.core.model.AssetCatalogManifest(
            schemaVersion = catalog.schemaVersion,
            engineVersion = catalog.engineVersion,
            contentVersion = catalog.contentVersion,
            buildStage = pt.tiago.bubblenight.core.model.CatalogBuildStage.valueOf(catalog.buildStage.name.uppercase()),
            expectedBaseCount = catalog.expectedBaseCount,
            expectedVariantCount = catalog.expectedVariantCount,
            chapters = catalog.chapters.map { pt.tiago.bubblenight.core.model.CatalogChapter(it.orbitId, it.firstOrdinal, it.lastOrdinal, it.titlePt) },
            levels = assetEntries,
        )
        val catalogBytes = (json.encodeToString(assetCatalog) + "\n").toByteArray()
        generated[destination.resolve("catalog.json")] = catalogBytes
        val manifest = AssetManifest(
            manifestVersion = 1,
            schemaVersion = catalog.schemaVersion,
            engineVersion = catalog.engineVersion,
            contentVersion = catalog.contentVersion,
            buildStage = catalog.buildStage.name,
            catalogSha256 = sha256(catalogBytes),
            files = generated.entries.filter { it.key.fileName.toString() != "manifest.json" }
                .map { (path, bytes) -> AssetFileHash(destination.relativize(path).toString().replace('\\', '/'), sha256(bytes)) }
                .sortedBy { it.path },
        )
        generated[destination.resolve("manifest.json")] = (json.encodeToString(manifest) + "\n").toByteArray()
        generated.forEach { (path, bytes) -> writeIfChanged(path, bytes) }
        println("Assets exported: ${assetEntries.size} base levels, ${assetEntries.size * 3} variants, ${generated.size} files.")
        println("Destination: $destination")
        println("Format: catalog.json indexes variants as core-serialized LevelDefinition files; certificates and templates stay out of the APK.")
        return 0
    }

    private fun validateCatalog(catalog: ContentCatalog, catalogFile: Path, projectRoot: Path): List<String> {
        val errors = mutableListOf<String>()
        fun problem(message: String) { errors += message }
        if (catalog.schemaVersion != 1) problem("unsupported schemaVersion: ${catalog.schemaVersion}")
        if (catalog.engineVersion != GameEngine.ENGINE_VERSION) problem("unsupported engineVersion: ${catalog.engineVersion}")
        if (catalog.contentVersion.isBlank()) problem("empty contentVersion")
        val expected = if (catalog.buildStage == BuildStage.expanding) catalog.expectedBaseCount else stageCounts[catalog.buildStage] ?: 0
        if (catalog.buildStage == BuildStage.expanding && expected !in 1..600) problem("expanding: expectedBaseCount must be between 1 and 600")
        if (catalog.expectedBaseCount != expected) problem("${catalog.buildStage}: expectedBaseCount should be $expected")
        if (catalog.expectedVariantCount != expected * 3) problem("${catalog.buildStage}: expectedVariantCount should be ${expected * 3}")
        if (catalog.levels.size != catalog.expectedBaseCount) problem("catalog declares ${catalog.expectedBaseCount} bases but contains ${catalog.levels.size} entries")
        val chapterCount = if (catalog.buildStage == BuildStage.full) 12 else (expected + 49) / 50
        if (catalog.chapters.size != chapterCount) problem("${catalog.buildStage} requires $chapterCount chapters in the catalog")
        val chapters = catalog.chapters.associateBy { it.orbitId }
        if (chapters.size != catalog.chapters.size) problem("repeated orbitId in chapters")
        catalog.chapters.forEach { chapter ->
            val orbit = chapter.orbitId.removePrefix("orbit-").toIntOrNull()
            if (orbit == null || orbit !in 1..12) problem("invalid orbitId in chapter: ${chapter.orbitId}")
            if (chapter.firstOrdinal > chapter.lastOrdinal) problem("inverted range in ${chapter.orbitId}")
            if (orbit != null && orbit in 1..12 && chapter.titlePt != orbitNames[orbit - 1]) problem("chapter name differs for ${chapter.orbitId}")
        }
        val ids = mutableSetOf<String>()
        val ordinals = mutableSetOf<Int>()
        val paths = mutableSetOf<String>()
        val familyCounts = mutableMapOf<String, Int>()
        val familyOwners = mutableMapOf<String, MutableList<SourceLevel>>()
        val completeFingerprints = mutableMapOf<String, String>()
        val structuralFingerprints = mutableMapOf<String, MutableSet<String>>()
        for (entry in catalog.levels.sortedBy { it.ordinal }) {
            if (!ids.add(entry.id)) problem("repeated ID: ${entry.id}")
            if (!ordinals.add(entry.ordinal)) problem("repeated ordinal: ${entry.ordinal}")
            if (!validId(entry.id, entry.ordinal)) problem("ID/ordinal mismatch: ${entry.id} / ${entry.ordinal}")
            if (entry.revision < 1) problem("${entry.id}: revision must be >= 1")
            if (!paths.add(entry.path)) problem("repeated path: ${entry.path}")
            val file = try { resolveEntry(projectRoot, entry.path) } catch (failure: Exception) {
                problem("${entry.id}: path refused (${failure.message})"); continue
            }
            if (!Files.isRegularFile(file)) { problem("${entry.id}: missing file: ${entry.path}"); continue }
            val bytes = Files.readAllBytes(file)
            if (!entry.sha256.matches(Regex("[0-9a-f]{64}")) || sha256(bytes) != entry.sha256)
                problem("${entry.id}: SHA-256 does not match the source content")
            val level = try { json.decodeFromString<SourceLevel>(bytes.toString(Charsets.UTF_8)) } catch (failure: Exception) {
                problem("${entry.id}: invalid JSON: ${failure.message}"); continue
            }
            if (level.schemaVersion != 1) problem("${entry.id}: unsupported schemaVersion")
            if (level.engineVersion != catalog.engineVersion) problem("${entry.id}: engineVersion differs from the catalog")
            if (level.id != entry.id || level.ordinal != entry.ordinal || level.revision != entry.revision)
                problem("${entry.id}: id, ordinal or revision differs from the catalog entry")
            val expectedOrbit = orbitFor(level.ordinal)
            if (level.orbitId != expectedOrbit) problem("${entry.id}: expected orbitId $expectedOrbit, got ${level.orbitId}")
            if (level.orbitId !in chapters) problem("${entry.id}: chapter ${level.orbitId} is not declared")
            familyCounts.merge(level.familyId, 1, Int::plus)
            familyOwners.getOrPut(level.familyId) { mutableListOf() } += level
            if (level.variants.keys != setOf("easy", "medium", "hard")) problem("${entry.id}: variants must be easy/medium/hard")
            for (difficulty in listOf("easy", "medium", "hard")) {
                val variant = level.variants[difficulty] ?: continue
                validateVariant(level, variant, difficulty, ::problem)
                val key = "$difficulty:${fingerprint(variant, includeObjective = true)}"
                val previous = completeFingerprints.putIfAbsent(key, level.id)
                if (previous != null && previous != level.id) problem("semantic duplicate ($difficulty) between $previous and ${level.id}")
                val structural = "$difficulty:${fingerprint(variant, includeObjective = false, includeColors = false)}"
                structuralFingerprints.getOrPut(structural) { mutableSetOf() } += level.id
            }
        }
        val expectedOrdinals = if (catalog.buildStage == BuildStage.expanding) 1..expected else when (catalog.buildStage) { BuildStage.slice -> 1..10; BuildStage.pilot -> 1..50; else -> 1..600 }
        if (ordinals != expectedOrdinals.toSet()) problem("ordinals do not cover exactly 1..${expectedOrdinals.last}")
        val expectedChapters = when (catalog.buildStage) {
            BuildStage.slice -> listOf(Triple("orbit-01", 1, 10))
            BuildStage.pilot -> listOf(Triple("orbit-01", 1, 50))
            BuildStage.expanding -> (1..chapterCount).map { orbit -> Triple("orbit-${orbit.toString().padStart(2, '0')}", (orbit - 1) * 50 + 1, minOf(orbit * 50, expected)) }
            BuildStage.full -> (1..12).map { orbit -> Triple("orbit-${orbit.toString().padStart(2, '0')}", (orbit - 1) * 50 + 1, orbit * 50) }
        }
        expectedChapters.forEach { (orbit, first, last) ->
            val chapter = chapters[orbit]
            if (chapter == null) problem("missing chapter: $orbit")
            else if (chapter.firstOrdinal != first || chapter.lastOrdinal != last)
                problem("$orbit: expected range $first..$last, got ${chapter.firstOrdinal}..${chapter.lastOrdinal}")
        }
        if (catalog.buildStage != BuildStage.slice) {
            val familyMaximum = kotlin.math.floor(expected * 0.15).toInt()
            familyCounts.forEach { (family, count) ->
                if (count > familyMaximum) {
                    val tutorialException = catalog.buildStage == BuildStage.pilot &&
                        familyOwners[family].orEmpty().any { owner ->
                            owner.ordinal <= 10 && owner.authorNotes?.startsWith("family-cap-exception:") == true &&
                                owner.authorNotes.substringAfter(':').isNotBlank()
                        }
                    if (!tutorialException)
                        problem("family $family exceeds 15% of the whole catalog ($count/$expected; maximum $familyMaximum); a tutorial exception in the pilot needs authorNotes starting with family-cap-exception: and a reason")
                }
            }
        }
        if (catalog.buildStage == BuildStage.full && familyCounts.size < 12)
            problem("a full catalog needs at least 12 structural families; found ${familyCounts.size}")
        structuralFingerprints.filterValues { it.size > 3 }.forEach { (fingerprint, owners) ->
            problem("structural fingerprint repeated in ${owners.size} bases (limit 3): ${fingerprint.take(24)}")
        }
        return errors
    }

    internal fun validateVariant(level: SourceLevel, variant: SourceVariant, difficulty: String, problem: (String) -> Unit) {
        val label = "${level.id}/$difficulty"
        if (!variant.seedHex.matches(Regex("[0-9a-f]{16}"))) problem("$label: seedHex must be 16 lowercase hexadecimal digits")
        if (variant.maxShots !in 1..100 || variant.threeStarMaxShots !in 1..variant.twoStarMaxShots || variant.twoStarMaxShots > variant.maxShots)
            problem("$label: shot limits out of order")
        if (variant.expectedMetrics.familyId != level.familyId) problem("$label: expectedMetrics.familyId differs from familyId")
        if (variant.expectedMetrics.minBounces !in 0..8) problem("$label: minBounces outside 0..8")
        if (variant.board.isEmpty() || variant.board.size > 119) problem("$label: invalid board size")
        val cells = variant.board.map { Cell(it.r, it.c) }
        if (cells.distinct().size != cells.size) problem("$label: duplicate cell")
        variant.board.forEach { cell ->
            val position = Cell(cell.r, cell.c)
            if (!HexGrid.isLegal(position)) problem("$label: illegal position r=${cell.r}, c=${cell.c}")
            if (cell.r !in 0..9) problem("$label: initial content outside rows 0..9")
            if (cell.kind !in setOf("color", "rock", "star")) problem("$label: unknown cell kind: ${cell.kind}")
            when (cell.kind) {
                "color" -> if (cell.color !in colorIds) problem("$label: invalid color: ${cell.color}")
                "rock", "star" -> if (cell.color != null) problem("$label: ${cell.kind} cannot declare a color")
            }
            if (cell.kind == "star" && cell.r == 0) problem("$label: STAR cannot be on row zero")
        }
        if (variant.board.none { it.kind == "color" }) problem("$label: at least one COLOR is required")
        val rowLimit = when (difficulty) { "easy" -> 7; "medium" -> 8; else -> 9 }
        if ((variant.board.maxOfOrNull { it.r } ?: 0) > rowLimit) problem("$label: depth exceeds row $rowLimit for $difficulty")
        val colors = variant.board.mapNotNull { it.color }.toSet()
        val colorRange = if (level.ordinal <= 10) 2..3 else when (difficulty) { "easy" -> 3..4; "medium" -> 4..5; else -> 5..6 }
        if (colors.size !in colorRange) problem("$label: ${colors.size} colors outside the profile $colorRange")
        if (!connectedToCeiling(cells)) problem("$label: board is not fully connected to row zero")
        val starCount = variant.board.count { it.kind == "star" }
        when (variant.objective.type) {
            "clear_colors" -> {
                if (variant.objective.total != null || variant.objective.colors != null) problem("$label: clear_colors takes no extra fields")
                if (starCount != 0) problem("$label: clear_colors cannot contain STAR")
            }
            "rescue_stars" -> {
                if (variant.objective.colors != null) problem("$label: rescue_stars does not take colors")
                if (variant.objective.total != starCount || starCount < 1) problem("$label: star total does not match the board")
            }
            "clear_color_set" -> {
                val targetColors = variant.objective.colors.orEmpty()
                if (variant.objective.total != null) problem("$label: clear_color_set does not take total")
                if (targetColors.isEmpty() || targetColors.distinct().size != targetColors.size) problem("$label: target colors empty or duplicated")
                if (!colors.containsAll(targetColors)) problem("$label: objective names a color that is not on the board")
                if (starCount != 0) problem("$label: clear_color_set cannot contain STAR")
            }
            else -> problem("$label: unknown objective ${variant.objective.type}")
        }
        val orbit = level.ordinal / 50 + if (level.ordinal % 50 == 0) 0 else 1
        if (orbit < 2 && variant.board.any { it.kind == "rock" }) problem("$label: ROCK appears before orbit 2")
        if (orbit < 3 && starCount > 0) problem("$label: STAR appears before orbit 3")
        if (orbit < 5 && variant.objective.type == "clear_color_set") problem("$label: color-set objective appears before orbit 5")
    }

    private fun connectedToCeiling(cells: List<Cell>): Boolean {
        if (cells.any { !HexGrid.isLegal(it) }) return false
        val occupied = cells.toSet()
        val seen = mutableSetOf<Cell>()
        val queue = ArrayDeque(cells.filter { it.row == 0 }.sortedWith(compareBy({ it.row }, { it.column })))
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            if (!seen.add(cell)) continue
            HexGrid.neighbors(cell).filter { it in occupied && it !in seen }.forEach(queue::addLast)
        }
        return seen.size == cells.size
    }

    private fun toCoreLevel(source: SourceLevel, difficulty: String, variant: SourceVariant): LevelDefinition {
        val board = Board.of(variant.board.map { cell ->
            val bubble = when (cell.kind) {
                "color" -> Bubble.Color(colorIds.getValue(cell.color!!))
                "rock" -> Bubble.Rock
                "star" -> Bubble.Star
                else -> error("validated kind expected")
            }
            CellBubble(Cell(cell.r, cell.c), bubble)
        })
        val objective = when (variant.objective.type) {
            "clear_colors" -> Objective.ClearColors
            "rescue_stars" -> Objective.RescueStars
            "clear_color_set" -> Objective.ClearColorSet(variant.objective.colors.orEmpty().map(colorIds::getValue))
            else -> error("validated objective expected")
        }
        return LevelDefinition(
            baseId = source.id,
            variantId = "${source.id}-$difficulty",
            revision = source.revision,
            semanticHash = semanticHash(source, difficulty, variant),
            seedHex = variant.seedHex,
            board = board,
            objective = objective,
            maxShots = variant.maxShots,
            twoStarMaxShots = variant.twoStarMaxShots,
            threeStarMaxShots = variant.threeStarMaxShots,
            schemaVersion = source.schemaVersion,
            engineVersion = source.engineVersion,
        )
    }

    /** Gameplay identity hash. Anti-clone fingerprints deliberately follow different normalization rules. */
    private fun semanticHash(source: SourceLevel, difficulty: String, variant: SourceVariant): String =
        sha256(semanticCanonical(source, difficulty, variant).toByteArray(Charsets.UTF_8))

    private fun semanticCanonical(source: SourceLevel, difficulty: String, variant: SourceVariant): String {
        val board = variant.board.sortedWith(compareBy({ it.r }, { it.c })).joinToString(",") { cell ->
            val colorField = cell.color?.let { ",\"color\":\"$it\"" }.orEmpty()
            "{\"r\":${cell.r},\"c\":${cell.c},\"kind\":\"${cell.kind}\"$colorField}"
        }
        val objective = when (variant.objective.type) {
            "clear_colors" -> "{\"type\":\"clear_colors\"}"
            "rescue_stars" -> "{\"type\":\"rescue_stars\",\"total\":${variant.objective.total}}"
            "clear_color_set" -> "{\"type\":\"clear_color_set\",\"colors\":[${variant.objective.colors.orEmpty().sorted().joinToString(",") { "\"$it\"" }}]}"
            else -> error("validated objective expected")
        }
        return """
            {"schemaVersion":${source.schemaVersion},"engineVersion":${source.engineVersion},"baseId":"${source.id}","variantId":"${source.id}-$difficulty","revision":${source.revision},"seedHex":"${variant.seedHex}","board":[$board],"objective":$objective,"maxShots":${variant.maxShots},"threeStarMaxShots":${variant.threeStarMaxShots},"twoStarMaxShots":${variant.twoStarMaxShots}}
        """.trimIndent()
    }

    private fun fingerprint(variant: SourceVariant, includeObjective: Boolean, includeColors: Boolean = true): String {
        fun encode(cells: List<SourceCell>, reflect: Boolean): String {
            val ordered = cells.map { cell ->
                val column = if (!reflect) cell.c else if (cell.r % 2 == 0) 8 - cell.c else 7 - cell.c
                Triple(cell.r, column, cell)
            }.sortedWith(compareBy({ it.first }, { it.second }))
            val ids = linkedMapOf<String, Int>()
            val boardText = ordered.joinToString(";") { (r, c, cell) ->
                val kind = when (cell.kind) {
                    "color" -> if (includeColors) "C${ids.getOrPut(cell.color!!) { ids.size }}" else "C"
                    "rock" -> "R"
                    "star" -> "S"
                    else -> "?"
                }
                "$r,$c,$kind"
            }
            val objectiveText = if (!includeObjective) "" else when (variant.objective.type) {
                "clear_colors" -> "|O:clear"
                "rescue_stars" -> "|O:stars:${variant.objective.total}"
                "clear_color_set" -> {
                    val targetIds = variant.objective.colors.orEmpty().mapNotNull(ids::get).sorted()
                    "|O:set:${targetIds.joinToString(",")}"
                }
                else -> "|O:${variant.objective.type}"
            }
            return boardText + objectiveText
        }
        val original = encode(variant.board, false)
        val reflected = encode(variant.board, true)
        return minOf(original, reflected)
    }

    private fun orbitFor(ordinal: Int): String = "orbit-${((ordinal - 1) / 50 + 1).toString().padStart(2, '0')}"

    private fun validId(id: String, ordinal: Int): Boolean = id == "level-${ordinal.toString().padStart(4, '0')}"

    private fun contentRoot(catalogFile: Path): Path {
        val contentDirectory = catalogFile.parent
        if (contentDirectory.fileName?.toString() != "content")
            throw CliFailure("The catalog must live at content/catalog.json so campaign paths stay inside content/levels.")
        return contentDirectory.parent
    }

    private fun resolveEntry(projectRoot: Path, relative: String): Path {
        if (!relative.startsWith("content/levels/") || relative.contains('\\') || relative.startsWith('/'))
            throw IllegalArgumentException("path fora de content/levels: $relative")
        val normalized = projectRoot.resolve(relative).normalize()
        val levelsRoot = projectRoot.resolve("content/levels").normalize()
        if (!normalized.startsWith(levelsRoot) || normalized == levelsRoot) throw IllegalArgumentException("path escapa de content/levels: $relative")
        return normalized
    }

    private inline fun <reified T> read(path: Path): T {
        if (!Files.isRegularFile(path)) throw CliFailure("File not found: $path")
        return try { json.decodeFromString(Files.readString(path)) }
        catch (failure: Exception) { throw CliFailure("Invalid JSON in $path: ${failure.message}") }
    }

    private fun writeIfChanged(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        if (Files.isRegularFile(path) && Files.readAllBytes(path).contentEquals(bytes)) return
        val temporary = path.resolveSibling(".${path.fileName}.tmp")
        Files.write(temporary, bytes)
        try { Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

@kotlinx.serialization.Serializable
private data class ContentReport(
    val schemaVersion: Int,
    val contentVersion: String,
    val engineVersion: Int,
    val buildStage: String,
    val baseCount: Int,
    val variantCount: Int,
    val familyCounts: Map<String, Int>,
    val orbitCounts: Map<String, Int>,
    val difficultyColorCounts: Map<String, Map<Int, Int>>,
    val proofStatus: String,
    val playtestStatus: String,
)

@kotlinx.serialization.Serializable
private data class AssetManifest(
    val manifestVersion: Int,
    val schemaVersion: Int,
    val engineVersion: Int,
    val contentVersion: String,
    val buildStage: String,
    val catalogSha256: String,
    val files: List<AssetFileHash>,
)

@kotlinx.serialization.Serializable
private data class AssetFileHash(val path: String, val sha256: String)

@kotlinx.serialization.Serializable
private data class SolveReport(
    val schemaVersion: Int,
    val contentVersion: String,
    val beamWidth: Int,
    val maxExpandedStatesPerCandidate: Int,
    val timeoutMillisPerCandidate: Long,
    val variants: List<SolveRow>,
)

@kotlinx.serialization.Serializable
private data class SolveRow(
    val baseId: String,
    val difficulty: String,
    val status: String,
    val shots: Int,
    val elapsedMillis: Long,
)
