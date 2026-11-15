package me.karboom.java.filaments;

import am.ik.yavi.builder.ValidatorBuilder;
import lombok.SneakyThrows;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import java.util.function.UnaryOperator;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FilamentsConfig 命名配置测试。
 *
 * 正交维度：
 *   D1 naming:       1=SNAKE 2=CAMEL 3=AS_IS 4=LOWER（列名与 jsonb 路径段共用同一函数）
 *   D2 生效范围:     1=where 条件键 2=rt/od 3=jsonb 路径段 4=聚合别名 5=嵌套对象序列化（mapper 配对）
 *   D3 作用域:       实例级独立 config（全局=自己持有一份 config 传多处）
 * 用例映射（naming × 生效范围 全覆盖）：
 *   N1  SNAKE/条件键+rt    N2  CAMEL/条件键+rt    N3  AS_IS/条件键
 *   N4  LOWER/条件键       N5  CAMEL vs AS_IS（查询里写下划线名）
 *   N6  naming × 路径段（三种）                   N7  naming × 嵌套对象写出键名
 *   N8  聚合别名 countWechatId + jsonb 分组别名  N9  实例级配置互不影响
 *   R1  真库：驼峰列名表全链路（where/rt/od/pk/pages）
 */
public class FilamentsConfigTest {

    public static class Nested2 {
        public Number totalCount;
        public String note;
    }

    public static class M {
        public String id;
        public String name;
        public String wechatId;
        public String type;
        public String location;
        public Nested2 extra;
        public OffsetDateTime createTime;
    }

    /** 驼峰列名的表（DDL 由本测试自己建，见 RealDb.ensureTable） */
    public static class C {
        public Long userId;
        public String wechatId;
    }

    static class FM extends Filaments<M> {
        FM(FilamentsConfig config) { super("filaments_user", config); }
        @Override
        public ValidatorBuilder<M> initValidator() { return new ValidatorBuilder<>(); }
    }

    static class FC extends Filaments<C> {
        // 驼峰列名表的主键是 userId：经构造函数传入
        FC(FilamentsConfig config) { super("filaments_camel", "userId", config); }
        @Override
        public ValidatorBuilder<C> initValidator() { return new ValidatorBuilder<>(); }
    }

    // 命名函数备选（同处声明的常量是同一实例，mapperForStyle 里可用 == 判定）
    static final UnaryOperator<String> SNAKE = FilamentsConfig::snake;
    static final UnaryOperator<String> CAMEL = FilamentsConfig::camel;
    static final UnaryOperator<String> LOWER = FilamentsConfig::lower;
    static final UnaryOperator<String> AS_IS = FilamentsConfig::asIs;

    private static FilamentsConfig cfg(UnaryOperator<String> naming) {
        var c = new FilamentsConfig();
        c.naming = naming;
        // 新语义：mapper 与 naming 需配对设置（库不提供工厂，测试自己手搓）
        c.mapper = mapperForStyle(naming);
        return c;
    }

    // 与 DEFAULT_MAPPER 同款 disable：保留时间原始 offset
    private static tools.jackson.databind.ObjectMapper mapperForStyle(UnaryOperator<String> naming) {
        var strategy = naming == CAMEL ? PropertyNamingStrategies.LOWER_CAMEL_CASE
                : naming == LOWER ? PropertyNamingStrategies.LOWER_CASE
                : naming == AS_IS ? null
                : PropertyNamingStrategies.SNAKE_CASE;
        return JsonMapper.builder()
                .propertyNamingStrategy(strategy)
                .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .build();
    }

    private static String sql(org.jooq.Query q) {
        return q.toString().replaceAll("\\s+", " ");
    }

    private static String render(FilamentsConfig config, Map<String, Object> query) {
        var f = new FM(config);
        return sql(f.buildSelect(DSL.using(SQLDialect.POSTGRES), query, null));
    }

    // =====================================================================
    // 列命名
    // =====================================================================

    @Nested
    @DisplayName("naming：列侧")
    class ColumnNaming {

        // N1: 默认 SNAKE
        @Test
        void n1_snakeIsDefault() {
            assertThat(new FilamentsConfig().naming.apply("wechatId")).isEqualTo("wechat_id");
            var s = render(new FilamentsConfig(), Map.of("wechatId", "x"));
            assertThat(s).contains("\"wechat_id\" = 'x'");
            assertThat(render(new FilamentsConfig(), Map.of("rt", "id,wechatId")))
                    .contains("select \"id\", \"wechat_id\"");
            assertThat(render(new FilamentsConfig(), Map.of("od", "-createTime")))
                    .contains("order by \"create_time\" desc");
        }

        // N2: CAMEL
        @Test
        void n2_camel() {
            var s = render(cfg(CAMEL), Map.of("wechatId", "x"));
            assertThat(s).contains("\"wechatId\" = 'x'");
            assertThat(render(cfg(CAMEL), Map.of("od", "createTime")))
                    .contains("order by \"createTime\" asc");
        }

        // N3: AS_IS
        @Test
        void n3_asIs() {
            assertThat(render(cfg(AS_IS), Map.of("wechatId", "x")))
                    .contains("\"wechatId\" = 'x'");
        }

        // N4: LOWER
        @Test
        void n4_lower() {
            assertThat(render(cfg(LOWER), Map.of("wechatId", "x")))
                    .contains("\"wechatid\" = 'x'");
        }

        // N5: CAMEL 与 AS_IS 的分野 —— 查询里写下划线名时，CAMEL 会折成驼峰列
        @Test
        void n5_camelFoldsSnakeInput() {
            var camel = cfg(CAMEL);
            assertThat(render(camel, Map.of("wechat_id", "x"))).contains("\"wechatId\" = 'x'");
            assertThat(render(cfg(AS_IS), Map.of("wechat_id", "x")))
                    .contains("\"wechat_id\" = 'x'");
        }
    }

    // =====================================================================
    // JSON 命名（路径段 + 嵌套对象）
    // =====================================================================

    @Nested
    @DisplayName("naming：JSON 侧")
    class JsonNaming {

        // N6: jsonb 查询路径段与列名共用 naming（合并后段0/路径段同一函数）
        @Test
        void n6_pathSegmentsFollowNaming() {
            assertThat(render(cfg(SNAKE), Map.of("extra.totalCount", "1")))
                    .contains("\"extra\" #>> '{total_count}'");
            assertThat(render(cfg(CAMEL), Map.of("extra.total_count", "1")))
                    .contains("\"extra\" #>> '{totalCount}'");
            assertThat(render(cfg(AS_IS), Map.of("extra.totalCount", "1")))
                    .contains("\"extra\" #>> '{totalCount}'");
            // 合并语义：段 0 也走 naming，camel 下不再折成下划线
            assertThat(render(cfg(CAMEL), Map.of("wechatInfo.totalCount", "1")))
                    .contains("\"wechatInfo\" #>> '{totalCount}'");
        }

        // N7: 写出键名 = mapper 的命名策略（与 naming 配对）
        @Test
        @SneakyThrows
        void n7_nestedKeysFollowJsonNaming() {
            var n = new Nested2();
            n.totalCount = 1;

            assertThat(cfg(SNAKE).mapper.writeValueAsString(n))
                    .contains("\"total_count\":1");
            assertThat(cfg(CAMEL).mapper.writeValueAsString(n))
                    .contains("\"totalCount\":1");
            assertThat(cfg(LOWER).mapper.writeValueAsString(n))
                    .contains("\"totalcount\":1");
            // AS_IS 不改写键名（内部是 null 命名策略，这里同时覆盖该分支）
            assertThat(cfg(AS_IS).mapper.writeValueAsString(n))
                    .contains("\"totalCount\":1");
        }

        // mapper 是普通实例字段：默认共享内置实例，换风格需显式赋值
        @Test
        void mapperIsPlainField() {
            assertThat(new FilamentsConfig().mapper)
                    .isSameAs(FilamentsConfig.DEFAULT_MAPPER);
            assertThat(cfg(CAMEL).mapper)
                    .isNotSameAs(FilamentsConfig.DEFAULT_MAPPER);
        }
    }

    // =====================================================================
    // 语法与分页配置
    // =====================================================================

    @Nested
    @DisplayName("sqlFuncList / nameSplitter / 默认分页")
    class SyntaxAndPaging {

        // 白名单可扩充 / 可收紧，且只影响本实例
        @Test
        void funcWhitelistIsConfigurable() {
            var custom = cfg(SNAKE);
            custom.sqlFuncList = List.of("count", "md5");
            var db = DSL.using(SQLDialect.POSTGRES);

            assertThat(sql(new FM(custom).buildSelect(db, Map.of("wechatId|md5", "x"), null)))
                    .contains("md5(\"wechat_id\")");

            // 默认白名单里的 abs 被移出后同样拒绝
            assertThatThrownBy(() -> sql(new FM(custom).buildSelect(db,
                    Map.of("wechatId|abs", "1"), null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sqlFuncList");

            // 默认配置不受影响
            assertThat(sql(new FM(new FilamentsConfig()).buildSelect(db,
                    Map.of("wechatId|abs", "1"), null))).contains("abs(\"wechat_id\")");
        }

        // 名字分隔符可换（示例用 #，避开 jsonb 的点分路径）
        @Test
        void nameSplitterIsConfigurable() {
            var custom = cfg(SNAKE);
            custom.nameSplitter = "#";
            var db = DSL.using(SQLDialect.POSTGRES);

            assertThat(sql(new FM(custom).buildSelect(db,
                    Map.of("extra.totalCount#substr(1,2)#ge", "5"), null)))
                    .contains("substr(\"extra\" #>> '{total_count}', 1,2) >= '5'");

            // 默认配置仍按 | 切分
            assertThat(sql(new FM(new FilamentsConfig()).buildSelect(db,
                    Map.of("type|in", "A,B"), null))).contains("in (");
        }

        // 实例级配置互不影响（真正生效路径见 RealDb.defaultPagingFromConfig）
        @Test
        void defaultPagingConfigIsPerInstance() {
            var custom = cfg(SNAKE);
            custom.page = 3L;
            custom.pageSize = 7L;

            assertThat(new FilamentsConfig().page).isEqualTo(1);
            assertThat(new FilamentsConfig().pageSize).isEqualTo(20);
            assertThat(custom.pageSize).isEqualTo(7);
        }
    }

    // =====================================================================
    // 别名与实例级作用域
    // =====================================================================

    @Nested
    @DisplayName("聚合别名与作用域")
    class AliasAndScope {

        // N8: 多词字段的别名不再吞掉大写字母；jsonb 分组列有可读的键
        @Test
        void n8_aggregationAlias() {
            var db = DSL.using(SQLDialect.POSTGRES);
            var f = new FM(new FilamentsConfig());
            var q = f.buildAggregation(db, Map.of("count", "wechatId"), Map.of(),
                    List.of("extra.totalCount"), null);
            assertThat(sql(q)).contains("as \"countWechatId\"")
                    .contains("as \"total_count\"");
        }

        // N9: 各实例配置互不影响（无全局 defaults，新鲜 config 恒为内置默认）
        @Test
        void n9_instanceScopeIsolated() {
            var snake = new FM(new FilamentsConfig());
            var camel = new FM(cfg(CAMEL));
            var query = new HashMap<String, Object>();
            query.put("wechatId", "x");

            assertThat(sql(snake.buildSelect(DSL.using(SQLDialect.POSTGRES), query, null)))
                    .contains("\"wechat_id\"");
            assertThat(sql(camel.buildSelect(DSL.using(SQLDialect.POSTGRES), query, null)))
                    .contains("\"wechatId\"");
            assertThat(new FilamentsConfig().naming.apply("wechatId")).isEqualTo("wechat_id");
        }

        // config 构造期定死：换命名风格要用新配置重新构造实例
        @Test
        void configFixedAtConstruction() {
            var f = new FM(new FilamentsConfig());
            assertThat(sql(f.buildSelect(DSL.using(SQLDialect.POSTGRES), Map.of("wechatId", "x"), null)))
                    .contains("\"wechat_id\"");

            var camel = new FM(cfg(CAMEL));
            assertThat(sql(camel.buildSelect(DSL.using(SQLDialect.POSTGRES), Map.of("wechatId", "x"), null)))
                    .contains("\"wechatId\"");
        }
    }

    // =====================================================================
    // 真库往返
    // =====================================================================

    @Nested
    @DisplayName("真库往返")
    class RealDb {

        DSLContext db;
        final FM snakeF = new FM(new FilamentsConfig());
        final FC camelColF = new FC(cfg(CAMEL));

        @SneakyThrows
        @BeforeEach
        void init() {
            var url = System.getenv("DB_URL");
            Assumptions.assumeTrue(url != null, "无 DB_URL，跳过真库用例");
            db = DSL.using(DriverManager.getConnection(url,
                    System.getenv("DB_USER"), System.getenv("DB_PASSWORD")), SQLDialect.POSTGRES);

            db.deleteFrom(DSL.table(DSL.name("filaments_user"))).execute();
            // 驼峰列名的表：本测试自建，验证 naming=CAMEL 的全链路
            db.execute("""
                    create table if not exists "filaments_camel" (
                        "userId" bigint primary key,
                        "wechatId" varchar(20) not null,
                        "createTime" timestamptz not null default current_timestamp
                    )""");
            db.deleteFrom(DSL.table(DSL.name("filaments_camel"))).execute();
        }

        private M user(String id) {
            var n = new Nested2();
            n.totalCount = 10;
            n.note = "note-" + id;
            var m = new M();
            m.id = id;
            m.name = "n" + id;
            m.wechatId = "wx_" + id;
            m.type = "A";
            m.location = "point(1, 2)";
            m.extra = n;
            m.createTime = OffsetDateTime.parse("2024-05-0" + id + "T10:11:12+08:00");
            return m;
        }

        // R2 已随 columnNaming/jsonNaming 合并删除：
        //   旧用例验证"snake 列 + camel jsonb 键"的混搭表，合并命名后该组合不可表达
        //   （naming=CAMEL 会连列名一起驼峰化，与 filaments_user 的 snake 列冲突）。

        // R1: 驼峰列名表的全链路（where / rt / od / pk / pages）
        @Test
        void camelColumnTableRoundTrip() {
            var rows = new java.util.ArrayList<C>();
            for (long i = 1; i <= 3; i++) {
                var c = new C();
                c.userId = i;
                c.wechatId = "wx" + i;
                rows.add(c);
            }
            camelColF.create(db, rows);

            assertThat(camelColF.get(db, Map.of("wechatId", "wx2")).getFirst().userId).isEqualTo(2L);
            assertThat(camelColF.getByIds(db, "3").wechatId).isEqualTo("wx3");

            var rt = camelColF.get(db, Map.of("rt", "userId,wechatId", "od", "-userId"));
            assertThat(rt.getFirst().userId).isEqualTo(3L);

            var page = camelColF.getByPages(db, new HashMap<>() {{
                put("pg", 1);
                put("pc", 2);
                put("od", "userId");
            }});
            assertThat(page.total).isEqualTo(3);
            assertThat(page.list).hasSize(2);

            // 默认 SNAKE 配置打到同一张表就会找错列，反证配置确实生效
            var wrong = new FC(new FilamentsConfig());
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> wrong.get(db, Map.of("userId", "1")))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class);
        }

        // 不传 p/pc 时按配置里的默认分页取数
        @Test
        void defaultPagingFromConfig() {
            snakeF.create(db, List.of(user("1"), user("2"), user("3")));

            var custom = cfg(SNAKE);
            custom.page = 2L;
            custom.pageSize = 1L;
            var small = new FM(custom);

            var page = small.getByPages(db, new HashMap<>() {{
                put("od", "id");
                put("pg", 1);   // 只有带 pg 才计算 total
            }});
            assertThat(page.total).isEqualTo(3);
            assertThat(page.list).hasSize(1);
            assertThat(page.list.getFirst().id).isEqualTo("2");

            // 默认配置还是 p=1 / pc=20
            assertThat(snakeF.getByPages(db, new HashMap<>()).list).hasSize(3);
        }

        @Test
        void snakeTableUnaffectedByDefaults() {
            snakeF.create(db, List.of(user("1"), user("2")));

            assertThat(snakeF.get(db, Map.of("extra.totalCount", "10"))).hasSize(2);
            assertThat(snakeF.get(db, Map.of("extra.total_count", "10"))).hasSize(2);
            assertThat(snakeF.getByIds(db, "1").wechatId).isEqualTo("wx_1");
        }
    }
}
