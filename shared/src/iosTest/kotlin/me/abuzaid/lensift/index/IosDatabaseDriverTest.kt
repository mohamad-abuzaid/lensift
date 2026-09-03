package me.abuzaid.lensift.index

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import me.abuzaid.lensift.db.LensiftDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosDatabaseDriverTest {
    @Test
    fun `native driver enables foreign keys and creates the Lensift schema`() {
        val driver = createConfiguredIosDatabaseDriver(
            name = ":memory:",
            driverFactory = IosLensiftDriverFactory { schema, name, onConfiguration ->
                NativeSqliteDriver(schema, name, onConfiguration = onConfiguration)
            },
        )
        try {
            assertEquals(1L, driver.longQuery("PRAGMA foreign_keys"))
            assertTrue(
                driver.stringListQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                ).containsAll(listOf("asset_analysis", "cleanup_history", "finding_group", "finding_member")),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `production-linked iOS factory uses the Lensift schema and database name`() {
        var receivedSchema: Any? = null
        var receivedName: String? = null
        var receivedConfiguration: ((DatabaseConfiguration) -> DatabaseConfiguration)? = null
        var configured: app.cash.sqldelight.db.SqlDriver? = null
        try {
            configured = createConfiguredIosDatabaseDriver(
                driverFactory = IosLensiftDriverFactory { schema, name, onConfiguration ->
                    receivedSchema = schema
                    receivedName = name
                    receivedConfiguration = onConfiguration
                    NativeSqliteDriver(schema, ":memory:", onConfiguration = onConfiguration)
                },
            )

            assertEquals(LensiftDatabase.Schema, receivedSchema)
            assertEquals("lensift.db", receivedName)
            assertTrue(receivedConfiguration != null)
            assertEquals(1L, requireNotNull(configured).longQuery("PRAGMA foreign_keys"))
        } finally {
            configured?.close()
        }
    }
}

private fun app.cash.sqldelight.db.SqlDriver.longQuery(sql: String): Long? = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        check(cursor.next().value)
        QueryResult.Value(cursor.getLong(0))
    },
    parameters = 0,
    binders = null,
).value

private fun app.cash.sqldelight.db.SqlDriver.stringListQuery(sql: String): List<String> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val values = mutableListOf<String>()
        while (cursor.next().value) values += requireNotNull(cursor.getString(0))
        QueryResult.Value(values)
    },
    parameters = 0,
    binders = null,
).value
