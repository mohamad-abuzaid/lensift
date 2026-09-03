package me.abuzaid.lensift.index

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDatabaseDriverTest {
    @Test
    fun `database driver configuration enables foreign keys on its connection`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.enableLensiftForeignKeys()

            val enabled = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA foreign_keys",
                mapper = { cursor ->
                    check(cursor.next().value)
                    QueryResult.Value(cursor.getLong(0))
                },
                parameters = 0,
                binders = null,
            ).value

            assertEquals(1L, enabled)
        } finally {
            driver.close()
        }
    }
}
