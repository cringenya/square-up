package squareup.application.account

import kotlin.uuid.Uuid

class Registration(val participantId: Uuid, val email: String, val login: String, val password: String)
