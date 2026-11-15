package me.karboom.java.filaments;

import org.jooq.DSLContext;

import java.util.List;

import static org.jooq.impl.DSL.using;

/**
 * 测试库连接与建表（真实 PG，不 mock）。
 * 连接参数可用环境变量 DB_JDBC_URL / DB_USER / DB_PASSWORD 覆盖，
 * 默认对接 ./script/start_test_env.sh 起的容器（127.0.0.1:5433/user_test，trust 免密），
 * 表统一放在独立 schema filaments_test，不碰 base.sql 的业务表。
 */
public class TestDb {

    static final String URL = env("DB_JDBC_URL",
            "jdbc:postgresql://127.0.0.1:5433/user_test?currentSchema=filaments_test&stringtype=unspecified");
    static final String USER = env("DB_USER", "test");
    static final String PASSWORD = env("DB_PASSWORD", "");

    static final String TABLE = "filaments_user";
    static final String CAMEL_TABLE = "filaments_camel";
    static final String LEGACY_TABLE = "filaments_legacy";

    static final String DDL = """
            create schema if not exists filaments_test;
            create table if not exists filaments_user (
              id varchar(64) primary key,
              name varchar(64),
              age int4,
              wechat_id varchar(64),
              type varchar(16),
              tags jsonb,
              address jsonb,
              profile jsonb,
              balance numeric(18, 4),
              create_time timestamptz
            );
            create table if not exists filaments_camel (
              id varchar(64) primary key,
              name varchar(64),
              "wechatId" varchar(64),
              "createTime" timestamptz
            );
            create table if not exists filaments_legacy (
              id varchar(64) primary key,
              create_time timestamptz
            );
            """;

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

    /** 幂等建表（@BeforeAll 调用） */
    static void initTables() {
        var db = db();
        List.of(DDL.split(";")).stream()
                .filter(stmt -> !stmt.isBlank())
                .forEach(db::execute);
    }

    /** 清三张测试表（@BeforeEach 调用，保证用例互不污染） */
    static void truncateAll() {
        db().execute("truncate table %s, %s, %s".formatted(TABLE, CAMEL_TABLE, LEGACY_TABLE));
    }
}
