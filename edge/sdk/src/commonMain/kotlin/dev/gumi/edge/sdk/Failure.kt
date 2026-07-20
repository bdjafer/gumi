package dev.gumi.edge.sdk

enum class FailureCategory {
    PERMISSION,
    UNAVAILABLE,
    TIMEOUT,
    DISCONNECTED,
    INCOMPATIBLE,
    UNAUTHORIZED,
    REPLAYED,
    CORRUPT,
    RESOURCE_EXHAUSTED,
    CANCELLED,
    REJECTED_POLICY,
    INTERNAL,
}

@JvmInline
value class FailureCode(val value: String) {
    init {
        require(value.matches(Regex("[A-Z][A-Z0-9_]{1,63}"))) {
            "Failure code must be a stable uppercase identifier: $value"
        }
    }

    override fun toString(): String = value
}

/**
 * An expected operational failure suitable for crossing runtime and shell boundaries.
 *
 * Evidence must already be redacted by the publisher. Raw media, credentials, transport addresses,
 * or provider payloads never belong here.
 */
data class ExpectedFailure(
    val category: FailureCategory,
    val code: FailureCode,
    val retryable: Boolean,
    val correlationId: CorrelationId? = null,
    val redactedEvidence: Map<String, String> = emptyMap(),
) {
    init {
        require(redactedEvidence.keys.all { it.isNotBlank() }) {
            "Failure evidence keys cannot be blank"
        }
    }
}

sealed interface OperationResult<out T> {
    data class Success<T>(val value: T) : OperationResult<T>

    data class Failure(val failure: ExpectedFailure) : OperationResult<Nothing>
}
