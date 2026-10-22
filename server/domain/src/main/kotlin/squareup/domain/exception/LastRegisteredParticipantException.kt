package squareup.domain.exception

class LastRegisteredParticipantException : IllegalStateException(
    "Another registered participant is required to remain in event"
)