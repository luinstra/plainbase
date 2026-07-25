package com.plainbase.frameworks.sqldelight

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bare-id and root-scoped resolution reads stay INDEX-SERVED in-image (STOP-3, §8.3): every live `None`, every
 * bare-permalink lookup and every root-scoped incumbent check runs a point-SELECT on `id_map`/`retired_binding`, and
 * an O(rows) `SCAN` on any of them is the `/p/{random-uuid}`-flood DoS. Asserts four `EXPLAIN QUERY PLAN`s are index
 * SEARCHes, not SCANs: the two `retired_binding` shapes (named `retired_binding_id` where we own the name), and the
 * two `id_map` shapes now served by the id-leading `UNIQUE(id, root)` (bare `WHERE id` AND root-scoped `WHERE id AND
 * root`). We do NOT assert the `UNIQUE(id, root)` autoindex NAME - SQLite generates `sqlite_autoindex_id_map_N` and the
 * ordinal depends on constraint order, the brittleness we avoid. RED: drop the constraint/index -> the plan is a SCAN.
 *
 * @Tag("native") + kotlin.test - the xerial JDBC/JNI seam (query-plan text crosses it), run under the native image.
 */
@Tag("native")
class IdMapIndexUsageTest {

    @Test
    fun `retired_binding and id_map WHERE id (+ root) are index-served in-image`() {
        val dir = Files.createTempDirectory("pb-native-explain")
        try {
            val dbPath = dir.resolve("plainbase.db")
            // A fresh file migrates to the current schema (v18), which includes retired_binding_id and UNIQUE(id, root).
            DatabaseFactory.createDriver(dbPath).use { /* schema created */ }

            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                conn.createStatement().use { st ->
                    fun plan(sql: String): String =
                        st.executeQuery("EXPLAIN QUERY PLAN $sql").use { rs ->
                            buildString { while (rs.next()) appendLine(rs.getString("detail")) }
                        }

                    val id = "x'00000000000000000000000000000000'"

                    // 1 + 2: both retired_binding shapes - the named index we own, never a SCAN.
                    val retiredShapes = listOf(
                        "SELECT root FROM retired_binding WHERE id = $id",
                        "SELECT * FROM retired_binding WHERE id = $id",
                    )
                    for (sql in retiredShapes) {
                        val p = plan(sql)
                        assertTrue(p.contains("retired_binding_id"), "expected the retired_binding_id index; plan was:\n$p")
                        assertFalse(p.contains("SCAN"), "expected an index search, not a full SCAN; plan was:\n$p")
                    }

                    // 3 + 4: both id_map shapes served by the id-leading UNIQUE(id, root) - STOP-3: neither may be a SCAN.
                    val bare = plan("SELECT root FROM id_map WHERE id = $id")
                    assertFalse(bare.contains("SCAN"), "id_map WHERE id must stay index-served (UNIQUE(id, root)); plan was:\n$bare")
                    val scoped = plan("SELECT * FROM id_map WHERE id = $id AND root = 'main'")
                    assertFalse(scoped.contains("SCAN"), "id_map WHERE id AND root must stay index-served; plan was:\n$scoped")
                }
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
