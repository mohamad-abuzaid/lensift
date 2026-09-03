package me.abuzaid.lensift.index

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import me.abuzaid.lensift.db.LensiftDatabase

fun createAndroidDatabaseDriver(context: Context): SqlDriver =
    AndroidSqliteDriver(
        schema = LensiftDatabase.Schema,
        context = context.applicationContext,
        name = "lensift.db",
    ).also(SqlDriver::enableLensiftForeignKeys)

internal fun SqlDriver.enableLensiftForeignKeys() {
    execute(
        identifier = null,
        sql = "PRAGMA foreign_keys=ON",
        parameters = 0,
        binders = null,
    ).value
}
