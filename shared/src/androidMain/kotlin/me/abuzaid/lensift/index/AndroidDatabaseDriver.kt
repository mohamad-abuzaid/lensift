package me.abuzaid.lensift.index

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import me.abuzaid.lensift.db.LensiftDatabase

fun createAndroidDatabaseDriver(context: Context): SqlDriver =
    createConfiguredLensiftDriver(
        rawContext = context,
        applicationContext = Context::getApplicationContext,
        driverFactory = LensiftDriverFactory { schema, applicationContext, name ->
            AndroidSqliteDriver(schema, applicationContext, name)
        },
    )

internal fun interface LensiftDriverFactory<C> {
    fun create(
        schema: SqlSchema<QueryResult.Value<Unit>>,
        applicationContext: C,
        name: String,
    ): SqlDriver
}

internal fun <C> createConfiguredLensiftDriver(
    rawContext: C,
    applicationContext: (C) -> C,
    driverFactory: LensiftDriverFactory<C>,
): SqlDriver {
    val driver = driverFactory.create(
        schema = LensiftDatabase.Schema,
        applicationContext = applicationContext(rawContext),
        name = LENSIFT_DATABASE_NAME,
    )
    driver.enableLensiftForeignKeys()
    return driver
}

private const val LENSIFT_DATABASE_NAME = "lensift.db"
