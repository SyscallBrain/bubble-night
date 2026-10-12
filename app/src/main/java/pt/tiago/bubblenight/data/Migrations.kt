package pt.tiago.bubblenight.data

/** Schema migrations stay explicit: unknown versions must never become an empty profile. */
object ProfileMigrations {
    fun migrate(profile: Profile): Profile {
        return when (profile.schemaVersion) {
            // Older builds kept up to 4096 receipts of each kind; trim to the current bounds.
            CURRENT_PROFILE_SCHEMA -> profile.copy(
                appliedOutcomeReceipts = profile.appliedOutcomeReceipts.takeLast(MAX_OUTCOME_RECEIPTS),
                appliedTurnReceipts = profile.appliedTurnReceipts.takeLast(MAX_TURN_RECEIPTS),
            )
            else -> throw UnsupportedProfileVersionException(profile.schemaVersion)
        }
    }
}

class UnsupportedProfileVersionException(val version: Int) :
    IllegalArgumentException("Unsupported Bubble Night profile schema: $version")

