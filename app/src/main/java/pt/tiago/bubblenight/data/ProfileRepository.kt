package pt.tiago.bubblenight.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pt.tiago.bubblenight.core.model.GameMode
import pt.tiago.bubblenight.core.model.GameState
import pt.tiago.bubblenight.core.model.Outcome
import pt.tiago.bubblenight.core.model.TurnResult
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

class ProfileRepository(context: Context) {
    private val holder = Stores.get(context.applicationContext)
    private val store = holder.store
    private val backup = holder.backup
    private val mutex = holder.mutex

    val profile: Flow<Profile> = store.data
    val recovery: StateFlow<ProfileRecovery> = holder.recovery

    suspend fun update(transform: (Profile) -> Profile): Profile = transact(transform)

    suspend fun saveSession(state: GameState): Profile = if (state.outcome != Outcome.READY) recordTurn(state) else update { current ->
        when (state.mode) {
            GameMode.CAMPAIGN -> current.copy(campaignSession = state)
            GameMode.ZEN -> current.copy(freeSession = FreeSession.Zen(state))
            GameMode.DAILY -> current.copy(dailySession = (current.dailySession ?: error("Start a dated daily session first")).copy(state = state))
            GameMode.ENDLESS -> {
                val previous = current.freeSession as? FreeSession.Endless
                    ?: error("Save EndlessRunSnapshot before saving an endless GameState")
                current.copy(freeSession = FreeSession.Endless(previous.run.copy(currentGame = state)))
            }
        }
    }

    suspend fun saveEndlessRun(run: EndlessRunSnapshot): Profile = update {
        it.copy(freeSession = FreeSession.Endless(run))
    }

    suspend fun saveDailySession(session: DailySession): Profile = update { it.copy(dailySession = session) }

    suspend fun clearSession(mode: GameMode): Profile = update { current ->
        when (mode) {
            GameMode.CAMPAIGN -> current.copy(campaignSession = null)
            GameMode.DAILY -> current.copy(dailySession = null)
            GameMode.ENDLESS -> if (current.freeSession is FreeSession.Endless) current.copy(freeSession = null) else current
            GameMode.ZEN -> if (current.freeSession is FreeSession.Zen) current.copy(freeSession = null) else current
        }
    }

    suspend fun recordTurn(state: GameState): Profile = update { ProfileReducer.recordTurn(it, state) }

    suspend fun recordTurn(result: TurnResult): Profile = if (result.accepted) {
        update { ProfileReducer.recordTurn(it, result.state, turnResult = result) }
    } else profile.first()

    suspend fun recordTurn(state: GameState, endlessRun: EndlessRunSnapshot): Profile =
        update { ProfileReducer.recordTurn(it, state, endlessRun) }

    suspend fun recordTurn(result: TurnResult, endlessRun: EndlessRunSnapshot): Profile = if (result.accepted) {
        update { ProfileReducer.recordTurn(it, result.state, endlessRun, result) }
    } else profile.first()

    /** Parse, checksum and semantically validate without writing; the host owns confirmation. */
    fun prepareImport(bytes: ByteArray): Profile = ProfileJson.prepareImport(bytes)

    /** Apply only after host confirmation. The prior valid state becomes the atomic recovery copy. */
    suspend fun applyImport(imported: Profile): Profile = mutex.withLock {
        // Freeze caller-owned collections and validate before enabling the recovery path.
        val validated = ProfileJson.prepareImport(ProfileJson.export(imported))
        holder.pendingImport.set(validated)
        try {
            commit { current -> validated.copy(revision = current.revision) }
        } finally {
            holder.pendingImport.set(null)
        }
    }

    suspend fun exportPayload(): ByteArray = ProfileJson.export(profile.first())

    private suspend fun transact(transform: (Profile) -> Profile): Profile = mutex.withLock {
        commit(transform)
    }

    private suspend fun commit(transform: (Profile) -> Profile): Profile {
        // The stored profile was validated when it was read or last committed, so it is not checked again here.
        return store.updateData { current ->
            val changed = transform(current)
            if (changed == current) current else {
                val next = ProfileJson.canonicalize(changed.copy(revision = current.revision.saturatingIncrement()))
                ProfileValidator.requireValid(next) // The only validation per commit; encoding trusts it.
                backup.writeValidated(current)
                next
            }
        }
    }

    private object Stores {
        private val holders = WeakHashMap<Context, Holder>()

        @Synchronized fun get(context: Context): Holder = holders[context] ?: create(context).also { holders[context] = it }

        private fun create(context: Context): Holder {
            val recovery = MutableStateFlow(ProfileRecovery.NONE)
            val backup = BackupManager(File(context.filesDir, "profile.previous.json"))
            val dataFile = context.dataStoreFile("profile.json")
            val pendingImport = AtomicReference<Profile?>(null)
            val handler = ReplaceFileCorruptionHandler<Profile> { error ->
                val decision = ProfileRecoveryPolicy.resolve(
                    pendingImport.get(), backup::readValidated,
                    { backup.preserveCorruptDataStore(dataFile) }, backup::preserveBeforeRecovery,
                ) ?: throw error
                recovery.value = decision.second
                decision.first
            }
            val store = DataStoreFactory.create(
                serializer = ProfileSerializer,
                corruptionHandler = handler,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { dataFile },
            )
            return Holder(store, backup, recovery, pendingImport)
        }
    }

    private data class Holder(
        val store: DataStore<Profile>,
        val backup: BackupManager,
        val recovery: MutableStateFlow<ProfileRecovery>,
        val pendingImport: AtomicReference<Profile?>,
        val mutex: Mutex = Mutex(),
    )

    private fun Long.saturatingIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1
}

