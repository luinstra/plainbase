package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.ColumnAdapter
import com.plainbase.domain.page.PageId

/**
 * The single PageId <-> bytes conversion in the tree (ID storage policy, decision log #6): every id
 * column is a 16-byte BLOB, `msb||lsb` big-endian, and this adapter at the repository boundary is
 * the only place that mapping exists — domain and API layers only ever see [PageId] / canonical
 * lowercase text. Delegates to [PageId]'s own BLOB round-trip; reflection-free (native gate).
 */
object PageIdColumnAdapter : ColumnAdapter<PageId, ByteArray> {

    override fun decode(databaseValue: ByteArray): PageId = PageId.fromByteArray(databaseValue)

    override fun encode(value: PageId): ByteArray = value.toByteArray()
}
