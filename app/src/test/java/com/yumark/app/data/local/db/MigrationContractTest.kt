package com.yumark.app.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 迁移与导出 schema 的一致性契约。
 *
 * 为什么必须有这层测试：迁移里的建表语句只在**升级**路径执行，全新安装走的是 Room
 * 自己生成的语句。所以本地装一个新 App 永远发现不了迁移写错——只有已经装了老版本的
 * 真实用户会在升级后启动时撞上 "Migration didn't properly handle: xxx" 闪退。
 *
 * FTS 表尤其容易写错：它没有 ALTER 可用，整条 CREATE VIRTUAL TABLE 得手写，
 * 而列定义之间到底是 `,` 还是 `, ` 这种差别，读 Room 源码推导过一次就会推错。
 * 唯一可核对的事实来源是 Room 导出的 `app/schemas/<db 类名>/<版本>.json`。
 */
class MigrationContractTest {

    /**
     * schema 导出目录。
     *
     * 逐级向上找而不是写死相对路径：Gradle 给测试进程的工作目录是模块目录（app/），
     * 但从 IDE 里单跑一个测试方法时通常是仓库根目录，写死哪一个都会在另一处失败。
     */
    private val schemaDir: File by lazy {
        val relative = "app/schemas/$DB_CLASS"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isDirectory }?.let { return@lazy it }
            File(dir, "schemas/$DB_CLASS").takeIf { it.isDirectory }?.let { return@lazy it }
            dir = dir.parentFile
        }
        error("找不到 Room 导出的 schema 目录（$relative）；先跑一次构建让 Room 重新导出")
    }

    /** 导出目录里最新的那个版本号，即 `@Database(version = ...)` 的当前值 */
    private val latestSchemaVersion: Int by lazy {
        schemaDir.listFiles()
            ?.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            ?.maxOrNull()
            ?: error("schema 目录里没有任何 <版本>.json：${schemaDir.absolutePath}")
    }

    /** 读某个版本的 schema，取指定表的 createSql，并把占位符换成真实表名 */
    private fun exportedCreateSql(version: Int, tableName: String): String {
        val file = File(schemaDir, "$version.json")
        assertThat(file.isFile).isTrue()
        val database = Json.parseToJsonElement(file.readText()).jsonObject
            .getValue("database").jsonObject
        assertThat(database.getValue("version").jsonPrimitive.int).isEqualTo(version)
        val entity = database.getValue("entities").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == tableName }
        return entity.getValue("createSql").jsonPrimitive.content
            .replace("\${TABLE_NAME}", tableName)
    }

    /**
     * 读某个版本的 schema，取指定表上某条**索引**的 createSql，并把占位符换成真实表名。
     *
     * 索引与建表语句一样必须逐字对上：索引名（Room 按 `index_<表>_<列>_<列>` 生成）
     * 或反引号、`, ` 分隔写错，迁移跑完 Room 的 schema 校验就报
     * "Migration didn't properly handle"。
     */
    private fun exportedIndexCreateSql(version: Int, tableName: String, indexName: String): String {
        val file = File(schemaDir, "$version.json")
        assertThat(file.isFile).isTrue()
        val entity = Json.parseToJsonElement(file.readText()).jsonObject
            .getValue("database").jsonObject
            .getValue("entities").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == tableName }
        val index = entity.getValue("indices").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == indexName }
        return index.getValue("createSql").jsonPrimitive.content
            .replace("\${TABLE_NAME}", tableName)
    }

    /** 跑一条迁移，收集它执行过的所有 SQL */
    private fun sqlExecutedBy(migration: androidx.room.migration.Migration): List<String> {
        val executed = mutableListOf<String>()
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.execSQL(capture(executed)) } just Runs
        migration.migrate(db)
        return executed
    }

    @Test
    fun `迁移 10 到 11 的建表语句与 Room 导出的 createSql 完全一致`() {
        val expected = exportedCreateSql(version = 11, tableName = "document_search")
        val executed = sqlExecutedBy(AppDatabase.MIGRATION_10_11)

        // 断言「相等」而不是「包含」：多一个空格、少一个反引号、列顺序不同，
        // 都会让 Room 启动校验失败，而这些差异肉眼极难发现
        assertThat(executed).containsExactly(expected)
    }

    @Test
    fun `迁移 11 到 12 的建索引语句与 Room 导出的 createSql 完全一致`() {
        val expectedDocuments = exportedIndexCreateSql(
            version = 12,
            tableName = "documents",
            indexName = "index_documents_folder_id_name"
        )
        val expectedFolders = exportedIndexCreateSql(
            version = 12,
            tableName = "folders",
            indexName = "index_folders_parent_id_name"
        )

        // relaxed 的 Cursor 让 moveToNext() 返回 false，两趟消重一条 UPDATE 都不会发出，
        // 剩下的正好是这两条建索引语句（消重本身的行为由 DuplicateNameResolverTest 覆盖）
        val executed = sqlExecutedBy(AppDatabase.MIGRATION_11_12)

        assertThat(executed).containsExactly(expectedDocuments, expectedFolders).inOrder()
    }

    @Test
    fun `迁移 12 到 13 的建表语句与 Room 导出的 createSql 完全一致`() {
        val expected = exportedCreateSql(version = 13, tableName = "sync_tombstones")
        val executed = sqlExecutedBy(AppDatabase.MIGRATION_12_13)

        // 墓碑表是删除能不能传到远端的唯一依据（见 SyncTombstoneEntity）。这条建表语句写错，
        // 老用户升级时会在 Room 的 schema 校验里闪退，而全新安装走 Room 自己生成的语句、
        // 本地怎么测都是好的
        assertThat(executed).containsExactly(expected)
    }

    @Test
    fun `ALL_MIGRATIONS 构成从 1 到最新 schema 版本的无缺口链条`() {
        val edges = AppDatabase.ALL_MIGRATIONS
            .map { it.startVersion to it.endVersion }
            .sortedBy { it.first }

        // 每条迁移都只跨一个版本，且首尾相接：断链意味着老用户升级时
        // Room 找不到路径，直接抛 IllegalStateException 闪退
        assertThat(edges).isEqualTo((1 until latestSchemaVersion).map { it to it + 1 })
    }

    private companion object {
        const val DB_CLASS = "com.yumark.app.data.local.db.AppDatabase"
    }
}
