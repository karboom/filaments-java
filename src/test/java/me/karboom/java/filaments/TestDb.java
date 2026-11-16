package me.karboom.java.filaments;

import lombok.SneakyThrows;
import org.jooq.DSLContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.using;

/**
 * 测试库连接与建表（真实 PG，不 mock）。
 * 连接参数可用环境变量 DB_JDBC_URL / DB_USER / DB_PASSWORD 覆盖，
 * 默认对接 ./script/start_test_env.sh 起的容器（127.0.0.1:5433/filaments，trust 免密）。
 * 表结构唯一真相是 script/base.sql，本类只负责读取并幂等执行，Java 侧不再另存 DDL。
 */
public class TestDb {

    static final String URL = env("DB_JDBC_URL",
            "jdbc:postgresql://127.0.0.1:5433/filaments?currentSchema=filaments_test&stringtype=unspecified");
    static final String USER = env("DB_USER", "test");
    static final String PASSWORD = env("DB_PASSWORD", "");

    static final String TABLE = "filaments_user";
    static final String CAMEL_TABLE = "filaments_camel";
    static final String LEGACY_TABLE = "filaments_legacy";

    /** 建表脚本相对仓库根的路径 */
    static final String DDL_PATH = "script/base.sql";

    private static String env(String key, String def) {
        var v = System.getenv(key);
        return v == null || v.isEmpty() ? def : v;
    }

    /**
     * 复用一个进程级 DSLContext（单连接），避免每次 using() 新建连接耗尽 PG 连接数。
     * 测试串行执行，无并发问题；进程结束由 JVM 关闭，不显式 close。
     */
    private static final DSLContext INSTANCE = using(URL, USER, PASSWORD);

    static DSLContext db() {
        return INSTANCE;
    }

    /** 幂等建表（@BeforeAll 调用）：读 script/base.sql 并逐条执行 */
    static void initTables() {
        ddl().forEach(db()::execute);
    }

    /** 清三张测试表（@BeforeEach 调用，保证用例互不污染） */
    static void truncateAll() {
        db().execute("truncate table %s, %s, %s".formatted(TABLE, CAMEL_TABLE, LEGACY_TABLE));
    }

    /**
     * 定位并拆分建表脚本，返回可独立执行的语句列表。
     * Gradle 与 IDE 的工作目录不同，故从当前目录逐级向上找到含 script/base.sql 的仓库根，找不到即抛错。
     * 拆分规则：丢弃以 -- 开头的注释行，再按分号切段（脚本因此约定注释内不写分号）。
     */
    @SneakyThrows
    private static List<String> ddl() {
        for (var dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            var script = dir.resolve(DDL_PATH);
            if (!Files.exists(script)) {
                continue;
            }
            var sql = Files.readString(script).lines()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            return Arrays.stream(sql.split(";"))
                    .map(String::strip)
                    .filter(stmt -> !stmt.isBlank())
                    .toList();
        }
        throw new Error("找不到建表脚本: " + DDL_PATH);
    }
}
