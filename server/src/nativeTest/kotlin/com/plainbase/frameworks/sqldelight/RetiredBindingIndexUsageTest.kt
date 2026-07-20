package com.plainbase.frameworks.sqldelight

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The C4 index (`15.sqm`) does its job in-image (6m, CLASS-2): the retired-root resolution behind every live None -
 * `SELECT root FROM retired_binding WHERE id = ?` - must be INDEX-SERVED, not a full `SCAN` (the O(tombstones)
 * `/p/{random-uuid}`-flood DoS the index removes). Asserts the `EXPLAIN QUERY PLAN` CONTAINS the substring
 * `retired_binding_id` (tolerating `SEARCH`/`USING` prefixes across xerial SQLite versions - NOT the literal
 * `USING INDEX retired_binding_id`) and is not a `SCAN`. RED: drop the `CREATE INDEX` -> the plan is a `SCAN` and
 * lacks the index name. Also pins that `id_map WHERE id = ?` stays index-served (the `UNIQUE(id)` auto-index in C4).
 *
 * @Tag("native") + kotlin.test - the xerial JDBC/JNI seam (query-plan text crosses it), run under the native image.
 */
@Tag("native")
class RetiredBindingIndexUsageTest {

    @Test
    fun `retired_binding WHERE id is index-served in-image, id_map WHERE id too`() {
        val dir = Files.createTempDirectory("pb-native-explain")
        try {
            val dbPath = dir.resolve("plainbase.db")
            // A fresh file migrates to the current schema (v16), which includes the retired_binding_id index.
            DatabaseFactory.createDriver(dbPath).use { /* schema created */ }

            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                conn.createStatement().use { st ->
                    val retiredPlan = st.executeQuery(
                        "EXPLAIN QUERY PLAN SELECT root FROM retired_binding WHERE id = x'00000000000000000000000000000000'",
                    ).use { rs -> buildString { while (rs.next()) appendLine(rs.getString("detail")) } }
                    assertTrue(retiredPlan.contains("retired_binding_id"), "expected the retired_binding_id index; plan was:\n$retiredPlan")
                    assertFalse(retiredPlan.contains("SCAN"), "expected an index search, not a full SCAN; plan was:\n$retiredPlan")

                    val idMapPlan = st.executeQuery(
                        "EXPLAIN QUERY PLAN SELECT root FROM id_map WHERE id = x'00000000000000000000000000000000'",
                    ).use { rs -> buildString { while (rs.next()) appendLine(rs.getString("detail")) } }
                    assertFalse(idMapPlan.contains("SCAN"), "id_map WHERE id must stay index-served (UNIQUE(id)); plan was:\n$idMapPlan")
                }
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
