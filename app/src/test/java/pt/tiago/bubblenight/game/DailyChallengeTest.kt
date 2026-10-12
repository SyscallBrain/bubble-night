package pt.tiago.bubblenight.game

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.bubblenight.core.model.*
import pt.tiago.bubblenight.core.rules.GameEngine
import pt.tiago.bubblenight.data.*
import java.time.LocalDate

class DailyChallengeTest {
    private val levels = (1..600).map { n ->
        RuntimeLevel("level-%04d".format(n), n, "orbit-%02d".format((n - 1) / 50 + 1), "Teste", "test", emptyList(), 1, emptyMap())
    }

    @Test fun challengeIsStableAcrossCatalogOrderAndDifferentForEachDifficulty() {
        val date = LocalDate.of(2026, 10, 12)
        val choices = Difficulty.entries.map { DailyChallenge.levelId(date, it, levels) }
        assertEquals(3, choices.distinct().size)
        for (d in Difficulty.entries) assertEquals(DailyChallenge.levelId(date, d, levels), DailyChallenge.levelId(date, d, levels.reversed()))
        assertNotEquals(DailyChallenge.levelId(date, Difficulty.EASY, levels), DailyChallenge.levelId(date.plusDays(1), Difficulty.EASY, levels))
    }

    @Test fun rotationVisitsAllLevelsBeforeRepeatingAndWorksInPilotCatalog() {
        val date = LocalDate.of(2026, 10, 12)
        val selected = (0L..599L).map { DailyChallenge.levelId(date.plusDays(it), Difficulty.HARD, levels) }
        assertEquals(600, selected.distinct().size)
        assertEquals(selected.first(), DailyChallenge.levelId(date.plusDays(600), Difficulty.HARD, levels))
        assertTrue(DailyChallenge.levelId(date, Difficulty.MEDIUM, levels.take(10)) in levels.take(10).map { it.id })
    }

    @Test fun dailyWinAndReplayDoNotChangeCampaignOrFreeSessionAndSurviveBackup() {
        val level = LevelDefinition("level-0001", "level-0001-medium", 1, "b".repeat(64), "0000000000000001",
            Board.of(listOf(CellBubble(Cell(0, 3), Bubble.Color(0)), CellBubble(Cell(0, 5), Bubble.Color(0)))),
            Objective.ClearColors, 5, 4, 3)
        val start = GameEngine.start(level, Difficulty.MEDIUM, GameMode.DAILY, "daily-1")
        val campaign = GameEngine.start(level, Difficulty.MEDIUM, GameMode.CAMPAIGN, "campaign")
        val zen = GameEngine.start(level, Difficulty.MEDIUM, GameMode.ZEN, "zen")
        val original = Profile(campaignSession = campaign, freeSession = FreeSession.Zen(zen), dailySession = DailySession("2026-10-12", start))
        val win = GameEngine.shoot(start, 0)
        assertEquals(Outcome.WON, win.state.outcome)
        val recorded = ProfileReducer.recordTurn(original, win.state, turnResult = win)
        assertEquals(original.campaignSession, recorded.campaignSession)
        assertEquals(original.freeSession, recorded.freeSession)
        assertEquals(original.progressByDifficulty, recorded.progressByDifficulty)
        assertEquals(original.statsByDifficulty, recorded.statsByDifficulty)
        val best = recorded.dailyResults.getValue("2026-10-12/MEDIUM")
        assertEquals(3, best.stars)
        assertEquals(345L, best.score)
        assertEquals(recorded, ProfileReducer.recordTurn(recorded, win.state, turnResult = win))
        assertEquals(recorded, ProfileJson.prepareImport(ProfileJson.export(recorded)))
        val replay = start.copy(sessionId = "daily-2")
        val repeated = ProfileReducer.recordTurn(recorded.copy(dailySession = DailySession("2026-10-12", replay)), GameEngine.shoot(replay, 0).state)
        assertEquals(best, repeated.dailyResults.getValue("2026-10-12/MEDIUM"))
    }

    @Test fun invalidDailyDateAndFutureFavoritesAreRejected() {
        assertTrue(ProfileValidator.violations(Profile(dailyResults = mapOf("2026-02-30/EASY" to DailyBest(3, 100, 1)))).isNotEmpty())
        assertTrue(ProfileValidator.violations(Profile(zenFavorites = setOf("level-0601"))).isNotEmpty())
    }
}
