package me.abuzaid.lensift.index

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import me.abuzaid.lensift.db.LensiftDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AndroidDatabaseDriverTest {
    @Test
    fun `production factory path selects application context schema name and foreign-key configuration`() {
        val rawContext = TestContext("raw")
        val applicationContext = TestContext("application")
        var applicationContextReads = 0
        var receivedContext: TestContext? = null
        var receivedName: String? = null
        var receivedSchema: Any? = null
        val sqlite = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val configured = createConfiguredLensiftDriver(
                rawContext = rawContext,
                applicationContext = {
                    applicationContextReads++
                    applicationContext
                },
                driverFactory = LensiftDriverFactory { schema, context, name ->
                    receivedSchema = schema
                    receivedContext = context
                    receivedName = name
                    sqlite
                },
            )

            assertSame(sqlite, configured)
            assertEquals(1, applicationContextReads)
            assertSame(applicationContext, receivedContext)
            assertSame(LensiftDatabase.Schema, receivedSchema)
            assertEquals("lensift.db", receivedName)
            assertEquals(1L, configured.foreignKeysEnabled())
        } finally {
            sqlite.close()
        }
    }

    @Test
    fun `database driver configuration enables foreign keys on its connection`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.enableLensiftForeignKeys()

            assertEquals(1L, driver.foreignKeysEnabled())
        } finally {
            driver.close()
        }
    }
}

private data class TestContext(val name: String)

private fun app.cash.sqldelight.db.SqlDriver.foreignKeysEnabled(): Long? = executeQuery(
    identifier = null,
    sql = "PRAGMA foreign_keys",
    mapper = { cursor ->
        check(cursor.next().value)
        QueryResult.Value(cursor.getLong(0))
    },
    parameters = 0,
    binders = null,
).value
