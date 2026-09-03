package me.abuzaid.lensift.index

import app.cash.sqldelight.db.SqlDriver

internal fun SqlDriver.enableLensiftForeignKeys() {
    execute(
        identifier = null,
        sql = "PRAGMA foreign_keys=ON",
        parameters = 0,
        binders = null,
    ).value
}
