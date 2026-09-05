package com.yumark.app.data.local.db

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移的设备侧验证。
 *
 * 与纯 JVM 的 [MigrationContractTest] 是**互补**关系，不是重复：
 * - MigrationContractTest 用 mockk 捕获 execSQL，断言迁移发出的建表语句与 Room 导出的
 *   createSql 逐字相等。它只比对字符串 —— 跑得快、能挂在每个 PR 上，但证明不了这条
 *   语句能被执行。
 * - 这里把语句真的喂给设备上的 SQLite。两件只有真机能验的事：
 *   1. **SQLite 接受这条语句。** `CREATE VIRTUAL TABLE … USING FTS4(… tokenize=unicode61)`
 *      写错时不是语法错误，而是运行期 `vtable constructor failed`（典型触发方式：列名
 *      叫 doc_id 还好，叫 docid 就撞上 FTS4 保留的 rowid 别名）。字符串比对永远发现不了。
 *   2. **迁移跑完后 Room 自己的 schema 校验能过。** 过不了就是老用户升级后启动即抛
 *      `IllegalStateException: Migration didn't properly handle: xxx`，App 直接闪退。
 *
 * 为什么这类 bug 只能靠迁移测试拦：迁移里的建表语句**只在升级路径执行**，全新安装走的是
 * Room 自己生成的语句。本地卸载重装永远是全新安装 —— 写错了本地一切正常、CI 也绿，
 * 炸的是已经装了老版本的真实用户，而且是启动即崩，没有任何补救窗口。
 *
 * 这些用例在 CI 上由 `instrumented` job（API 30 x86_64 模拟器）跑，触发条件是推 main
 * 或手动触发；PR 上只做 `assembleDebugAndroidTest` 编译检查。改完迁移不要等推 main，
 * 从 Actions 页面手动触发一次。
 *
 * 方法名不带空格是硬约束：dex 的 SimpleName 只有 040 及以上版本允许空格字符，而 D8 按
 * minSdk 决定 dex 版本（本项目 minSdk 26），带空格的反引号方法名会让
 * assembleDebugAndroidTest 直接失败，报错是
 * "Space characters in SimpleName '…' are not allowed prior to DEX version 040"。
 * 汉字落在 U+2030..U+D7FF，所有 dex 版本都允许，所以中文名可以用、空格不行。
 * （MigrationContractTest 只在 JVM 跑、不过 dex，那边的方法名带空格没事，别照抄过来。）
 */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {

    /**
     * schema 从**测试 APK 的 assets** 里读，路径是 `<AppDatabase 的 canonicalName>/<版本>.json`。
     * 挂载不需要任何手写配置：app/build.gradle.kts 里的 `room { schemaDirectory(...) }` 会让
     * Room 的 Gradle 插件注册 `copyRoomSchemasToAndroidTestAssets<Variant>`，由它把 schema
     * 送进 androidTest 的 assets（已验证 11 个 json 全部落到 mergeDebugAndroidTestAssets 输出）。
     * 报 "Cannot find the schema file in the assets folder" 就是 `room {}` 那段没生效，
     * 不是迁移写错了，别顺着迁移查。
     *
     * 传 Class 而不是过时的 assetsFolder 字符串重载：后者把路径拼错要到运行期才发现，
     * 而 Class 让 canonicalName 由编译器保证。
     */
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `从版本1逐级迁移到最新版本每一步都过Room校验`() {
        // createDatabase 按 1.json 建库并写好版本 1 的 identity hash，
        // 这就是「三年前装了 v1 的用户」的库。建完立刻关掉，下一步要重新打开它。
        helper.createDatabase(DB_CHAIN, START_VERSION).close()

        // 逐级跑而不是一步到底：一步到底只能告诉你「最终 schema 不对」，
        // 逐级能精确指出是哪一条迁移把表建歪了 —— 排查成本差一个量级。
        var reached = START_VERSION
        orderedMigrations().forEach { migration ->
            // 顺带钉住链条首尾相接。断链时 Room 抛的是「找不到迁移路径」，
            // 报错指向整条路径而不是缺失的那一环。
            assertThat(migration.startVersion).isEqualTo(reached)

            helper.runMigrationsAndValidate(
                DB_CHAIN,
                migration.endVersion,
                VALIDATE_DROPPED_TABLES,
                migration
            ).use { db ->
                // 迁移完成后 SQLite 的 user_version 必须被推到目标版本；
                // 停在原地意味着 Room 认为这一步没跑，下次启动还会再跑一遍。
                assertThat(db.version).isEqualTo(migration.endVersion)
            }
            reached = migration.endVersion
        }

        assertThat(reached).isEqualTo(latestVersion)
    }

    @Test
    fun `从版本1一次性迁移到最新版本`() {
        helper.createDatabase(DB_ONE_SHOT, START_VERSION).close()

        // 把整个 ALL_MIGRATIONS 交给 Room 自己找 1 → 最新 的路径，这正是跨多个版本升级的
        // 真实老用户走的代码路径（DatabaseModule 里也是这么 addMigrations 的）。
        // 逐级测过不等于跨版本没问题：某条迁移的副作用被后一条覆盖、或者 Room 挑了一条
        // 与逐级不同的路径（有多条边可选时），只有这里会暴露。
        helper.runMigrationsAndValidate(
            DB_ONE_SHOT,
            latestVersion,
            VALIDATE_DROPPED_TABLES,
            *AppDatabase.ALL_MIGRATIONS
        ).use { db ->
            assertThat(db.version).isEqualTo(latestVersion)
        }
    }

    @Test
    fun `版本1写入的文档在迁移到最新版本后完整保留`() {
        val v1 = helper.createDatabase(DB_DATA, START_VERSION)

        // 手写 INSERT、不用 DAO：DAO 是按**当前**实体生成的，拿它写不进版本 1 的表结构。
        // 列名与列序照 app/schemas/…/1.json 里 documents 的 createSql 抄，不是照
        // DocumentEntity 猜 —— 实体后来可能改过，1.json 才是版本 1 的事实来源。
        // 值直接写成 SQL 字面量而不走 bindArgs：全程只用 execSQL(String) 这一个重载，
        // 省掉「不同 androidx.sqlite 版本 bindArgs 元素可空性声明不同」带来的编译风险。
        // folder_id 写 NULL 而不是造一条 folders 记录：这一列有外键指向 folders，
        // 造记录只会让用例多一个与本题无关的失败点。
        v1.execSQL(
            "INSERT INTO documents " +
                "(id, name, folder_id, created_at, updated_at, is_favorite, word_count, character_count) " +
                "VALUES ('${DOC_ID}', '${DOC_NAME}', NULL, ${CREATED_AT}, ${UPDATED_AT}, 1, 42, 137)"
        )
        v1.close()

        val migrated = helper.runMigrationsAndValidate(
            DB_DATA,
            latestVersion,
            VALIDATE_DROPPED_TABLES,
            *AppDatabase.ALL_MIGRATIONS
        )

        // 企业级迁移的核心承诺就是不丢用户数据，所以这条必须有。
        // documents 表从版本 1 到现在没被任何迁移改过，所以今天验的是「没有哪条迁移
        // 顺手重建了它」；真正的价值在将来 —— 某天有人为了加列走「建新表 + 复制 + 删旧表」
        // 的路子，写漏一列或漏一行，这里立刻红，而全新安装的手动测试永远发现不了。
        // 逐列断言而不是只数行数：行还在但某列被写成默认值，同样是丢数据。
        migrated.use { db ->
            db.query(
                "SELECT name, folder_id, created_at, updated_at, is_favorite, word_count, character_count " +
                    "FROM documents WHERE id = '${DOC_ID}'"
            ).use { cursor ->
                assertThat(cursor.count).isEqualTo(1)
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo(DOC_NAME)
                assertThat(cursor.isNull(1)).isTrue()
                assertThat(cursor.getLong(2)).isEqualTo(CREATED_AT)
                assertThat(cursor.getLong(3)).isEqualTo(UPDATED_AT)
                assertThat(cursor.getInt(4)).isEqualTo(1)
                assertThat(cursor.getInt(5)).isEqualTo(42)
                assertThat(cursor.getInt(6)).isEqualTo(137)
            }
        }
    }

    @Test
    fun `迁移出来的FTS4虚拟表能写入并被MATCH命中`() {
        helper.createDatabase(DB_FTS, START_VERSION).close()

        helper.runMigrationsAndValidate(
            DB_FTS,
            latestVersion,
            VALIDATE_DROPPED_TABLES,
            *AppDatabase.ALL_MIGRATIONS
        ).use { db ->
            // 「建出来了」和「能用」是两件事：CREATE VIRTUAL TABLE 成功之后，
            // 分词器名字拼错、影子表没建全之类的问题要到第一次写入或查询才炸。
            // 生产侧第一次搜索会惰性回填整库索引（DocumentRepositoryImpl.ensureSearchIndex），
            // 那时候炸就是用户点搜索直接失败。
            //
            // 写入内容刻意做成 FtsTextNormalizer 的产物形态（CJK 单字之间插空格）：
            // unicode61 按「非字母数字」切词，中文句子没空格会整句变成一个巨型 token，
            // 于是搜任何子串都搜不到。生产侧靠 FtsTextNormalizer 规避，这里必须用同一种
            // 形态，否则验的「能 MATCH」和线上不是同一回事。
            db.execSQL(
                "INSERT INTO document_search (doc_id, name, content) VALUES " +
                    "('${FTS_DOC_ID}', '迁 移 测 试', '这 是 一 段 用 于 验 证 fts4 的 正 文')"
            )

            // MATCH 左侧必须是**表名**才会同时搜 name 与 content（写成 content MATCH 只搜正文），
            // 与 DocumentSearchDao.searchDocIds 的写法保持一致。
            // 外层单引号是 SQL 字符串，内层双引号是 FTS 短语 —— 短语要求 token 相邻，
            // 这才还原出「子串匹配」的语义。
            assertThat(matchedDocIds(db, "\"验 证 fts4\"")).containsExactly(FTS_DOC_ID)

            // 只出现在 name 列的短语也要命中：这条钉的是「MATCH 表名会搜所有列」这一性质，
            // 一旦有人把 DAO 改成 `content MATCH ?`，按标题搜文档就会静默失效。
            assertThat(matchedDocIds(db, "\"迁 移\"")).containsExactly(FTS_DOC_ID)

            // 反向断言：token 顺序不对的短语不该命中。
            // 少了这条，上面两句就算退化成「命中一切」也看不出来。
            assertThat(matchedDocIds(db, "\"fts4 验 证\"")).isEmpty()
        }
    }

    @Test
    fun `迁移到12时同文件夹下的重名文档被消重且不撞上已存在的括号名`() {
        val v11 = helper.createDatabase(DB_DEDUPE, DEDUPE_FROM)
        // 列名与列序照 app/schemas/…/11.json 抄；`order` 是 SQLite 关键字，必须带反引号
        v11.execSQL(
            "INSERT INTO folders (id, name, parent_id, created_at, `order`) " +
                "VALUES ('${FOLDER_ID}', 'F', NULL, 1, 0)"
        )
        insertDoc(v11, id = "d1", name = "笔记", folder = "'${FOLDER_ID}'", createdAt = 10)
        insertDoc(v11, id = "d2", name = "笔记", folder = "'${FOLDER_ID}'", createdAt = 20)
        insertDoc(v11, id = "d3", name = "笔记 (2)", folder = "'${FOLDER_ID}'", createdAt = 30)
        // 根目录（folder_id 为 NULL）的两条同名：SQLite 不认为两个 NULL 相等，这一组
        // 不会让 CREATE UNIQUE INDEX 失败，迁移就不该动用户的名字
        insertDoc(v11, id = "r1", name = "根笔记", folder = "NULL", createdAt = 40)
        insertDoc(v11, id = "r2", name = "根笔记", folder = "NULL", createdAt = 50)
        v11.close()

        helper.runMigrationsAndValidate(
            DB_DEDUPE,
            DEDUPE_TO,
            VALIDATE_DROPPED_TABLES,
            AppDatabase.MIGRATION_11_12
        ).use { db ->
            // 要点全在 d2：它的首选新名「笔记 (2)」正好是 d3 已经占着的。只避开「已定稿」
            // 的名字时，消重会自己制造出一组新的重复，紧随其后的 CREATE UNIQUE INDEX
            // 于是失败 → 迁移失败 → 老用户升级后启动即崩，且本地全新安装测不出来。
            assertThat(nameOf(db, "d1")).isEqualTo("笔记")
            assertThat(nameOf(db, "d2")).isEqualTo("笔记 (3)")
            assertThat(nameOf(db, "d3")).isEqualTo("笔记 (2)")
            // 根目录那两条原样留着：这是**已知局限**（见 MIGRATION_11_12 的注释），
            // 不是漏改。哪天这两句开始失败，说明有人改了根目录的存储方式
            assertThat(nameOf(db, "r1")).isEqualTo("根笔记")
            assertThat(nameOf(db, "r2")).isEqualTo("根笔记")
        }
    }

    @Test
    fun `迁移到12后同文件夹重名插入被唯一索引拒绝而根目录仍然放行`() {
        val v11 = helper.createDatabase(DB_UNIQUE, DEDUPE_FROM)
        v11.execSQL(
            "INSERT INTO folders (id, name, parent_id, created_at, `order`) " +
                "VALUES ('${FOLDER_ID}', 'F', NULL, 1, 0)"
        )
        insertDoc(v11, id = "d1", name = "笔记", folder = "'${FOLDER_ID}'", createdAt = 10)
        v11.close()

        helper.runMigrationsAndValidate(
            DB_UNIQUE,
            DEDUPE_TO,
            VALIDATE_DROPPED_TABLES,
            AppDatabase.MIGRATION_11_12
        ).use { db ->
            // 索引「建出来了」和「真的在拦」是两件事：索引名对上了 Room 的校验就能过，
            // 但 UNIQUE 漏写、列顺序写错一样能过校验，只有真插一行才看得出来。
            // 这就是加这条索引的全部意义——代码里的「先查再写」挡不住并发交错。
            val duplicate = runCatching {
                insertDoc(db, id = "d2", name = "笔记", folder = "'${FOLDER_ID}'", createdAt = 20)
            }
            assertThat(duplicate.isFailure).isTrue()

            // 根目录不受保护（NULL != NULL），这条断言钉的是**已知局限**而不是期望行为：
            // 它哪天失败，说明根目录改成了非 NULL 表示，记得同步改 MIGRATION_11_12 的注释
            insertDoc(db, id = "r1", name = "根笔记", folder = "NULL", createdAt = 30)
            insertDoc(db, id = "r2", name = "根笔记", folder = "NULL", createdAt = 40)
            assertThat(nameOf(db, "r2")).isEqualTo("根笔记")
        }
    }

    /**
     * 跑一次 MATCH，收集命中的 doc_id。
     *
     * 短语直接拼进 SQL 而不走 bindArgs：本文件全程只用 query(String) / execSQL(String)
     * 这两个单参重载，短语内容都是本文件里的字面量，没有外部输入。
     * 生产侧走的是 DAO 的绑定参数版本，那里才需要防注入。
     */
    private fun matchedDocIds(db: SupportSQLiteDatabase, phrase: String): List<String> {
        val ids = mutableListOf<String>()
        db.query("SELECT doc_id FROM document_search WHERE document_search MATCH '${phrase}'")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    ids += cursor.getString(0)
                }
            }
        return ids
    }

    /**
     * 按版本 11 的列结构插一行文档。
     *
     * [folder] 传的是 SQL 字面量（`'f1'` 或 `NULL`）而不是 String?：本文件全程只用
     * execSQL(String) 这一个单参重载，可空列必须由调用方决定写字面量还是 NULL。
     */
    private fun insertDoc(
        db: SupportSQLiteDatabase,
        id: String,
        name: String,
        folder: String,
        createdAt: Long
    ) {
        db.execSQL(
            "INSERT INTO documents " +
                "(id, name, folder_id, created_at, updated_at, is_favorite, word_count, character_count) " +
                "VALUES ('${id}', '${name}', ${folder}, ${createdAt}, ${createdAt}, 0, 0, 0)"
        )
    }

    /** 读一行文档当前的名字；行不存在返回 null（消重只该改名，绝不该删行） */
    private fun nameOf(db: SupportSQLiteDatabase, id: String): String? {
        db.query("SELECT name FROM documents WHERE id = '${id}'").use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    /** ALL_MIGRATIONS 按 startVersion 排好序后的完整链条 */
    private fun orderedMigrations(): List<Migration> =
        AppDatabase.ALL_MIGRATIONS.sortedBy { it.startVersion }

    /**
     * 当前数据库版本。
     *
     * 从 ALL_MIGRATIONS 推导而不是写死 11：版本一涨，上面几条用例自动覆盖新增的那条迁移，
     * 不需要有人记得回来改测试 —— 而「改了 @Database(version) 却没人补测试」正是迁移 bug
     * 最常见的来源。@Database 注解是 BINARY 保留级别，运行期反射读不到版本号，
     * 所以只能走这条路。
     *
     * 「ALL_MIGRATIONS 与导出 schema 的版本号首尾相接、无缺口」由纯 JVM 的
     * MigrationContractTest 盯着，所以这里可以放心把它当作完整链条用。
     */
    private val latestVersion: Int
        get() = AppDatabase.ALL_MIGRATIONS.maxOf { it.endVersion }

    private companion object {
        /** 老用户能有的最早版本；也是导出 schema 里存在的最小版本号 */
        const val START_VERSION = 1

        /**
         * 每个用例一个独立的库文件名。
         *
         * createDatabase 会先删掉同名旧文件，而删除在文件仍被上一个用例持有时会失败并抛
         * "There is a database file and I could not delete it"（MigrationTestHelper 作为
         * TestWatcher 只在用例结束后才关它托管的连接）。共用文件名会让失败与用例执行顺序
         * 相关、极难复现；换个名字就没有这个耦合。
         */
        const val DB_CHAIN = "migration-test-chain.db"
        const val DB_ONE_SHOT = "migration-test-one-shot.db"
        const val DB_DATA = "migration-test-data.db"
        const val DB_FTS = "migration-test-fts.db"
        const val DB_DEDUPE = "migration-test-dedupe.db"
        const val DB_UNIQUE = "migration-test-unique.db"

        /**
         * 唯一索引那条迁移的起止版本。
         *
         * 这两个写死 11/12 而不是从 ALL_MIGRATIONS 推导：上面几条链条用例验的是「任意版本
         * 都能升到最新」，而这两条验的是 MIGRATION_11_12 这一条具体迁移的行为，
         * 版本号是用例语义的一部分。版本再涨也不该改这里。
         */
        const val DEDUPE_FROM = 11
        const val DEDUPE_TO = 12

        /** 消重用例里承载同名文档的那个文件夹（根目录不受唯一索引保护，必须有真实父级） */
        const val FOLDER_ID = "mig-folder-1"

        /**
         * 顺带检查「库里有 schema 之外的残留表」。
         *
         * 少了它，「某条迁移建了一张实体早就删掉的旧表」这类脏活查不出来 ——
         * Room 的正向校验只看该有的表在不在，不看多出来什么。
         *
         * FTS 的影子表（document_search 的 _content/_segdir/_segments/_stat/_docsize）
         * 由 Room 的 FtsEntityBundle.shadowTableNames 自动列入白名单，
         * room_master_table / android_metadata / sqlite_sequence 也在白名单里，
         * 所以开着它不会因为那张 FTS 表误报。
         *
         * 万一某个 Room 版本的白名单没覆盖影子表，报错形如
         * "Migration failed. Unexpected table document_search_docsize" ——
         * 那时把这个常量改成 false 即可，全文件只有这一处。
         */
        const val VALIDATE_DROPPED_TABLES = true

        const val DOC_ID = "mig-doc-1"
        const val DOC_NAME = "迁移测试文档"
        const val CREATED_AT = 1_700_000_000_000L
        const val UPDATED_AT = 1_700_000_001_000L
        const val FTS_DOC_ID = "fts-doc-1"
    }
}
