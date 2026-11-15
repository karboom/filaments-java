package me.karboom.java.filaments;

import am.ik.yavi.builder.ValidatorBuilder;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import lombok.SneakyThrows;

import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.name;

/**
 * 驼峰 / 下划线列名归一化回归测试。
 *
 * 覆盖三条曾各自为政的路径：
 *   1. where 条件键（曾经完全不转换，直接报 column does not exist）
 *   2. 连续大写命名（hutool toUnderlineCase 不降格：userID -> user_ID）
 *   3. 各入口（create / update / rt / od / group / pk）统一走 Filaments.columnName
 *
 * jsonb 路径段（extra.totalCount 之类 JSON 键）按 FilamentsConfig.naming 归一化（与列名共用），
 * 与写侧 config.mapper 的命名策略按约定配对（默认同为 SNAKE），因此驼峰/下划线两种写法都命中；
 * 见 FilamentsConfigTest。
 */
public class CamelSnakeProbeTest {

    static class U extends Filaments<User> {
        U() { super("filaments_user"); }

        @Override
        public ValidatorBuilder<User> initValidator() {
            return ValidatorBuilder.<User>of().constraint(User::getId, "id", c -> c.notNull());
        }
    }

    private final U f = new U();

    private String sql(org.jooq.Query q) {
        return q.toString().replaceAll("\\s+", " ");
    }

    private String render(Map<String, Object> query) {
        return sql(f.buildSelect(DSL.using(SQLDialect.POSTGRES), query, null));
    }

    // =====================================================================
    // columnName 归一化规则本身
    // =====================================================================

    /**
     * 正交维度：
     *   D1 输入形态: 1=驼峰  2=下划线  3=连续大写  4=含数字
     *   D2 期望:     全小写下划线，且对已是下划线的输入幂等
     */
    @Nested
    @DisplayName("columnName 归一化")
    class ColumnName {

        @Test
        void camelToSnake() {
            assertThat(FilamentsConfig.snake("wechatId")).isEqualTo("wechat_id");
            assertThat(FilamentsConfig.snake("createTime")).isEqualTo("create_time");
            assertThat(FilamentsConfig.snake("actionIds")).isEqualTo("action_ids");
        }

        @Test
        void snakeInputIsIdempotent() {
            assertThat(FilamentsConfig.snake("create_time")).isEqualTo("create_time");
            assertThat(FilamentsConfig.snake("user_name")).isEqualTo("user_name");
            assertThat(FilamentsConfig.snake("x__y")).isEqualTo("x__y");
            assertThat(FilamentsConfig.snake("id")).isEqualTo("id");
        }

        // 曾经的坑：连续大写不降格，而 jOOQ 经 name() 加引号是精确匹配
        @Test
        void consecutiveUppercaseIsFolded() {
            assertThat(FilamentsConfig.snake("userID")).isEqualTo("user_id");
            assertThat(FilamentsConfig.snake("USER")).isEqualTo("user");
            assertThat(FilamentsConfig.snake("HTTPStatus")).isEqualTo("http_status");
        }

        @Test
        void digitsArePreserved() {
            assertThat(FilamentsConfig.snake("orderTime2")).isEqualTo("order_time2");
            assertThat(FilamentsConfig.snake("v2Data")).isEqualTo("v2_data");
        }
    }

    // =====================================================================
    // 各入口渲染一致性（无库依赖）
    // =====================================================================

    @Nested
    @DisplayName("各入口渲染一致")
    class Rendering {

        @Test
        void conditionKeyIsNormalized() {
            assertThat(render(Map.of("wechatId", "xx"))).contains("where (\"wechat_id\" = 'xx')");
            assertThat(render(Map.of("createTime|ge", "2024-01-01T00:00:00+08:00")))
                    .contains("\"create_time\" >=");
        }

        @Test
        void conditionKeyWithFuncChainIsNormalized() {
            assertThat(render(Map.of("wechatId|substr(1,2)", "xx")))
                    .contains("substr(\"wechat_id\", 1,2)");
            assertThat(render(Map.of("actionIds|intersect", "a1")))
                    .contains("jsonb_path_exists(\"action_ids\"");
        }

        @Test
        void rtAndOdUseSameColumnName() {
            assertThat(render(Map.of("rt", "id,wechatId"))).contains("select \"id\", \"wechat_id\"");
            assertThat(render(Map.of("od", "-createTime"))).contains("order by \"create_time\" desc");
            // 显式写库里的下划线名也指向同一列
            assertThat(render(Map.of("od", "-create_time"))).contains("order by \"create_time\" desc");
        }

        @Test
        void aggregationGroupAndTargetAreNormalized() {
            var db = DSL.using(SQLDialect.POSTGRES);
            var q = f.buildAggregation(db, Map.of("count", "wechatId"), Map.of(), List.of("createTime"), null);
            assertThat(sql(q)).contains("select \"create_time\", count(\"wechat_id\")")
                    .contains("group by \"create_time\"");
        }

        // 段 0 按列名归一化，其后的路径段按 jsonKey 归一化（默认 SNAKE）
        @Test
        void jsonbColumnAndPathBothNormalized() {
            assertThat(render(Map.of("wechatInfo.age", "1")))
                    .contains("\"wechat_info\" #>> '{age}'");
            assertThat(render(Map.of("extra.totalCount", "10")))
                    .contains("\"extra\" #>> '{total_count}'");
            assertThat(render(Map.of("extra.total_count", "10")))
                    .contains("\"extra\" #>> '{total_count}'");
        }
    }

    // =====================================================================
    // 真库行为
    // =====================================================================

    @Nested
    @DisplayName("真库驼峰列查询")
    class RealDb {

        DSLContext db;

        @SneakyThrows
        @BeforeEach
        void init() {
            var url = System.getenv("DB_URL");
            Assumptions.assumeTrue(url != null, "无 DB_URL，跳过真库用例");
            db = DSL.using(DriverManager.getConnection(url,
                    System.getenv("DB_USER"), System.getenv("DB_PASSWORD")), SQLDialect.POSTGRES);

            db.deleteFrom(DSL.table(name("filaments_user"))).execute();
            var users = new java.util.ArrayList<User>();
            for (int i = 1; i <= 3; i++) {
                var e = new User.Extra();
                e.setAge(20 + i);
                e.setTotalCount(10);
                e.setEmail("u" + i + "@e.com");
                var u = new User();
                u.setId(String.valueOf(i));
                u.setName("n" + i);
                u.setWechatId("wx_" + i);
                u.setType("A");
                u.setRoles(List.of(new User.Role("r", new User.RoleGrant(true))));
                u.setExtra(e);
                u.setLocation("point(1, 2)");
                u.setActionIds(List.of("a" + i));
                users.add(u);
            }
            // create 也走同一条归一化链路（列名带引号写入）
            f.create(db, users);
        }

        private List<String> ids(List<User> list) {
            return list.stream().map(u -> u.id).toList();
        }

        // 曾经：ERROR: column "wechatId" does not exist
        @Test
        void camelConditionWorks() {
            var res = f.get(db, new HashMap<>() {{
                put("wechatId", "wx_2");
                put("od", "id");
            }});
            assertThat(ids(res)).containsExactly("2");
        }

        @Test
        void camelConditionWithFuncChainAndAlias() {
            var res = f.get(db, new HashMap<>() {{
                put("wx::wechatId|substr(4,4)", "2");
                put("ac::actionIds|intersect", "a3");
                put("lg", "!(wx,ac)");
            }});
            assertThat(ids(res)).containsExactlyInAnyOrder("2", "3");
        }

        @Test
        void camelConditionCombinedWithProjection() {
            var res = f.get(db, new HashMap<>() {{
                put("wechatId|in", "wx_1,wx_3");
                put("rt", "id,wechatId");
                put("od", "-wechatId");
            }});
            assertThat(res).hasSize(2);
            assertThat(res.getFirst().wechatId).isEqualTo("wx_3");
        }

        @Test
        void snakeAndCamelAddressSameColumn() {
            assertThat(ids(f.get(db, Map.of("wechatId", "wx_1"))))
                    .isEqualTo(ids(f.get(db, Map.of("wechat_id", "wx_1"))));
        }

        @Test
        void aggregationGroupByCamelColumn() {
            var rows = f.aggregation(db, Map.of("count", "id"),
                    new HashMap<>() {{put("type", "A");}}, List.of("createTime"), null);
            assertThat(rows).hasSize(1);
            // intoMaps 的键是真实列名（不带引用引号）
            assertThat(rows.getFirst()).containsKey("create_time");
        }

        @Test
        void pagesWithCamelCondition() {
            var page = f.getByPages(db, new HashMap<>() {{
                put("wechatId|like", "wx_%");
                put("pg", 1);
                put("pc", 2);
                put("p", 1);
                put("od", "id");
            }});
            assertThat(page.total).isEqualTo(3);
            assertThat(ids(page.list)).containsExactly("1", "2");
        }

        // 写侧 Jackson 用 SNAKE_CASE，读侧路径段同样归一化 → 驼峰与下划线两种写法都命中
        @Test
        void jsonbPathBothSpellingsMatch() {
            assertThat(f.get(db, Map.of("extra.total_count", "10"))).hasSize(3);
            assertThat(f.get(db, Map.of("extra.totalCount", "10"))).hasSize(3);
        }
    }
}
