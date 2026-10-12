package pt.tiago.bubblenight.data

import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The recovery copy is separate from DataStore; AtomicFile owns its .bak/.new protocol. */
class BackupManager(private val backupFile: File) {
    private val lock = ReentrantLock()
    private val atomicFile = AtomicFile(backupFile)

    /** [profile] must be the stored profile: it was validated when it was read or committed. */
    fun writeValidated(profile: Profile) = lock.withLock {
        val bytes = ProfileJson.exportValidated(ProfileJson.canonicalize(profile))
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    fun readValidated(): Profile? = lock.withLock {
        if (!backupFile.exists() && !File(backupFile.path + ".bak").exists()) return null
        try {
            val input = atomicFile.openRead()
            input.use { stream ->
                val bytes = stream.readProfileBytes(MAX_PROFILE_BYTES_FOR_BACKUP + 1)
                require(bytes.size <= MAX_PROFILE_BYTES_FOR_BACKUP) { "Recovery backup is too large" }
                ProfileJson.prepareImport(bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Preserve an unreadable DataStore file for manual recovery before surfacing the error. */
    fun preserveCorruptDataStore(file: File): Unit = lock.withLock {
        if (!file.exists()) return@withLock
        val target = File.createTempFile("${file.name}.corrupt-", ".json", file.parentFile)
        FileInputStream(file).use { input -> FileOutputStream(target).use { output ->
            input.copyTo(output)
            output.fd.sync()
        } }
    }

    fun preserveBeforeRecovery() = lock.withLock {
        preserveCorruptDataStore(backupFile)
        preserveCorruptDataStore(File(backupFile.path + ".bak"))
    }

    private companion object { const val MAX_PROFILE_BYTES_FOR_BACKUP = 8 * 1024 * 1024 }
}

