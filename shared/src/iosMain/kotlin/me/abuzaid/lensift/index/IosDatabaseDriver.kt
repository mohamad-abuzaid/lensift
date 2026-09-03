package me.abuzaid.lensift.index

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import me.abuzaid.lensift.db.LensiftDatabase

fun createIosDatabaseDriver(): SqlDriver = createConfiguredIosDatabaseDriver()

internal fun interface IosLensiftDriverFactory {
    fun create(
        schema: SqlSchema<QueryResult.Value<Unit>>,
        name: String,
        onConfiguration: (DatabaseConfiguration) -> DatabaseConfiguration,
    ): SqlDriver
}

internal fun createConfiguredIosDatabaseDriver(
    name: String = LENSIFT_DATABASE_NAME,
    driverFactory: IosLensiftDriverFactory = IosLensiftDriverFactory { schema, databaseName, configure ->
        NativeSqliteDriver(schema, databaseName, onConfiguration = configure)
    },
): SqlDriver {
    return driverFactory.create(LensiftDatabase.Schema, name, ::enableIosForeignKeys)
}

private fun enableIosForeignKeys(configuration: DatabaseConfiguration): DatabaseConfiguration =
    configuration.copy(
        extendedConfig = configuration.extendedConfig.copy(foreignKeyConstraints = true),
    )

private const val LENSIFT_DATABASE_NAME = "lensift.db"
