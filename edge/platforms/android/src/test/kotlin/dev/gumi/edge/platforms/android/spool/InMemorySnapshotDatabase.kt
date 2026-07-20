package dev.gumi.edge.platforms.android.spool

internal class InMemorySnapshotDatabase : SnapshotDatabase {
    private var row: EncryptedSnapshotRow? = null
    var loseNextCommitResponse: Boolean = false

    override fun <T> transaction(block: SnapshotTransaction.() -> T): T = synchronized(this) {
        var staged = row?.copy(encryptedState = row!!.encryptedState.copyOf())
        val transaction = object : SnapshotTransaction {
            override fun read(): EncryptedSnapshotRow? =
                staged?.copy(encryptedState = staged!!.encryptedState.copyOf())

            override fun insertIfAbsent(row: EncryptedSnapshotRow): Boolean {
                if (staged != null) return false
                staged = row.copy(encryptedState = row.encryptedState.copyOf())
                return true
            }

            override fun replaceIfRevision(
                expectedRevision: String,
                next: EncryptedSnapshotRow,
            ): Boolean {
                if (staged?.revisionToken != expectedRevision) return false
                staged = next.copy(encryptedState = next.encryptedState.copyOf())
                return true
            }
        }
        val result = transaction.block()
        row = staged
        if (loseNextCommitResponse) {
            loseNextCommitResponse = false
            throw SnapshotCommitOutcomeUnknownException()
        }
        result
    }

    fun snapshot(): EncryptedSnapshotRow? = synchronized(this) {
        row?.copy(encryptedState = row!!.encryptedState.copyOf())
    }

    fun replaceForTest(next: EncryptedSnapshotRow) = synchronized(this) {
        row = next.copy(encryptedState = next.encryptedState.copyOf())
    }

    fun deleteForTest() = synchronized(this) {
        row = null
    }

    override fun close() = Unit
}
