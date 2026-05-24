package com.macropilot.app.ai

class AiAssistPolicy {
    fun evaluate(request: AiAssistRequest): AiAssistResponse? {
        if (!request.redacted) {
            return AiAssistResponse(
                status = AiAssistStatus.BLOCKED,
                message = "AI request blocked because input is not marked redacted"
            )
        }
        if (!request.userVisible) {
            return AiAssistResponse(
                status = AiAssistStatus.BLOCKED,
                message = "AI request blocked because it is not visible to the user"
            )
        }
        return null
    }
}
