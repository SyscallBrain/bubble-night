package pt.tiago.bubblenight.data

/** Pure recovery decision; the caller performs DataStore's atomic replacement. */
internal object ProfileRecoveryPolicy {
    fun resolve(
        confirmedImport: Profile?,
        readBackup: () -> Profile?,
        preserveOriginal: () -> Unit,
        preserveBackup: () -> Unit,
    ): Pair<Profile, ProfileRecovery>? {
        if (confirmedImport != null) {
            ProfileValidator.requireValid(confirmedImport)
            preserveOriginal()
            preserveBackup()
            return confirmedImport to ProfileRecovery.RECOVERED_FROM_IMPORT
        }
        val backup = readBackup()
        preserveOriginal()
        return backup?.let { ProfileValidator.requireValid(it) to ProfileRecovery.RECOVERED_FROM_BACKUP }
    }
}
