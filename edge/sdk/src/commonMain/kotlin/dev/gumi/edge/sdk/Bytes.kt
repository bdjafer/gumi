package dev.gumi.edge.sdk

/**
 * Immutable byte payload whose default representation never reveals content. Protocol and media
 * boundaries use an explicit copy operation so structured logs cannot leak audio or credentials.
 */
class OpaqueBytes private constructor(private val bytes: ByteArray) {
    val size: Int get() = bytes.size

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is OpaqueBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "OpaqueBytes([redacted], size=$size)"

    companion object {
        fun copyOf(bytes: ByteArray): OpaqueBytes = OpaqueBytes(bytes.copyOf())
    }
}
