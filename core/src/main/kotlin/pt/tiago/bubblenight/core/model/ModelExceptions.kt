package pt.tiago.bubblenight.core.model

/** Invalid authored content cannot be used to create a game session. */
class LevelDefinitionException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
