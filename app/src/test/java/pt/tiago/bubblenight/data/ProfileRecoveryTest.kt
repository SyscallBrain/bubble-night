package pt.tiago.bubblenight.data

import org.junit.Assert.*
import org.junit.Test

class ProfileRecoveryTest {
    @Test fun corruptPrimaryAndBackupDoNotReset() {
        var preserved = false
        val result = ProfileRecoveryPolicy.resolve(null, { null }, { preserved = true }, {})
        assertNull(result)
        assertTrue(preserved)
    }

    @Test fun confirmedImportRecoversEvenWithoutReadableBackupAndPreservesBothFiles() {
        val imported = Profile(settings = Settings(sound = false))
        var originalPreserved = false
        var backupPreserved = false
        val result = ProfileRecoveryPolicy.resolve(imported,
            { error("Corrupt backup must not prevent confirmed import") },
            { originalPreserved = true }, { backupPreserved = true })
        assertEquals(imported, result!!.first)
        assertEquals(ProfileRecovery.RECOVERED_FROM_IMPORT, result.second)
        assertTrue(originalPreserved && backupPreserved)
    }

    @Test fun preservationFailurePreventsRecoveryReplacement() {
        assertThrows(java.io.IOException::class.java) {
            ProfileRecoveryPolicy.resolve(Profile(), { null },
                { throw java.io.IOException("disk full") }, {})
        }
    }

    @Test fun validBackupRecoversWithExplicitStatus() {
        val backup = Profile(revision = 3)
        val result = ProfileRecoveryPolicy.resolve(null, { backup }, {}, {})
        assertEquals(backup, result!!.first)
        assertEquals(ProfileRecovery.RECOVERED_FROM_BACKUP, result.second)
    }
}
