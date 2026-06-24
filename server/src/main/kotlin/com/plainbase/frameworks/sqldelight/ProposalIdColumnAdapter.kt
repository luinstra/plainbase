package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.ColumnAdapter
import com.plainbase.domain.page.ProposalId

/**
 * The single ProposalId <-> bytes conversion in the tree (the [PageIdColumnAdapter] idiom): the
 * `proposals.id` column is a 16-byte BLOB, `msb||lsb` big-endian, and this adapter at the repository
 * boundary is the only place that mapping exists — domain and API layers only ever see [ProposalId] /
 * canonical lowercase text. Delegates to [ProposalId]'s own BLOB round-trip; reflection-free (native gate).
 */
object ProposalIdColumnAdapter : ColumnAdapter<ProposalId, ByteArray> {

    override fun decode(databaseValue: ByteArray): ProposalId = ProposalId.fromByteArray(databaseValue)

    override fun encode(value: ProposalId): ByteArray = value.toByteArray()
}
