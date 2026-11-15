package me.karboom.java.filaments;

import me.karboom.java.filaments.spec.FieldCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.SQLDialect.POSTGRES;
import static org.jooq.impl.DSL.using;

/**
 * buildConditionFromFieldCondition：针对入参 FieldCondition 的 {字段形态 × 函数链 × 操作符} 正交设计。
 *
 * <pre>
 * 正交表 L9(3^3)（字段列 / 函数链列 / 操作符列，逐行组合）：
 * ┌────┬──────────────┬──────────────┬──────────────┐
 * │ 组合│ field        │ funcChain    │ operator     │
 * ├────┼──────────────┼──────────────┼──────────────┤
 * │ 1  │ name         │ []           │ EQ           │
 * │ 2  │ name         │ [lower]      │ IN           │
 * │ 3  │ name         │ [substr(1,2)]│ LIKE         │
 * │ 4  │ address.city │ []           │ BETWEEN      │
 * │ 5  │ address.city │ [lower]      │ GT           │
 * │ 6  │ address.city │ [substr(1,2)]│ NE           │
 * │ 7  │ `tags`       │ []           │ INTERSECT    │
 * │ 8  │ `tags`       │ [lower]      │ NOT_IN       │
 * │ 9  │ `tags`       │ [substr(1,2)]│ LT           │
 * └────┴──────────────┴──────────────┴──────────────┘
 * 异常路径（负向）：非法字段名 / 函数链越白名单 / 未知操作符。
 * 渲染断言用无连接 DSLContext（不 mock）；实际取数在 FilamentsTest 走真库。
 * </pre>
 */
@Timeout(5)
class BuildConditionTest {

    TestFilaments filament;

    @BeforeEach
    void setUp() {
        filament = new TestFilaments(TestDb.TABLE);
    }

    private FieldCondition cond(String field, List<String> chain, String operator, Object... values) {
        var c = new FieldCondition();
        c.setField(field);
        c.setFuncChain(chain);
        c.setOperator(operator);
        c.setValues(Arrays.asList(values));
        return c;
    }

    private String sql(FieldCondition c) {
        return using(POSTGRES).render(filament.buildConditionFromFieldCondition(c));
    }

    // region 正交 L9

    @Nested
    class OrthogonalL9 {

        @Test
        void combo1_eq_plain_noFunc() {
            assertThat(sql(cond("name", List.of(), FieldCondition.EQ, "x"))).isEqualTo("(\"name\" = ?)");
        }

        @Test
        void combo2_in_plain_lower() {
            assertThat(sql(cond("name", List.of("lower"), FieldCondition.IN, "a", "b")))
                    .isEqualTo("(lower(\"name\") in (? , ?))");
        }

        @Test
        void combo3_like_plain_substr() {
            assertThat(sql(cond("name", List.of("substr(1,2)"), FieldCondition.LIKE, "%x%")))
                    .isEqualTo("(substr(\"name\", 1,2) like ?)");
        }

        @Test
        void combo4_between_dotted_noFunc() {
            assertThat(sql(cond("address.city", List.of(), FieldCondition.BETWEEN, 1, 10)))
                    .isEqualTo("(\"address\" #>> '{city}' between (? and ?))");
        }

        @Test
        void combo5_gt_dotted_lower() {
            assertThat(sql(cond("address.city", List.of("lower"), FieldCondition.GT, 5)))
                    .isEqualTo("(lower(\"address\" #>> '{city}') > ?)");
        }

        @Test
        void combo6_ne_dotted_substr() {
            assertThat(sql(cond("address.city", List.of("substr(1,2)"), FieldCondition.NE, "ab")))
                    .isEqualTo("(substr(\"address\" #>> '{city}', 1,2) <> ?)");
        }

        @Test
        void combo7_intersect_backtick_noFunc() {
            assertThat(sql(cond("`tags`", List.of(), FieldCondition.INTERSECT, "a", "b")))
                    .contains("jsonb_path_exists(\"tags\"")
                    .doesNotContain("'a'"); // 值走绑定参数，不进 SQL 文本
        }

        @Test
        void combo8_notin_backtick_lower() {
            assertThat(sql(cond("`tags`", List.of("lower"), FieldCondition.NOT_IN, "a", "b")))
                    .isEqualTo("(lower(\"tags\") not in (? , ?))");
        }

        @Test
        void combo9_lt_backtick_substr() {
            assertThat(sql(cond("`age`", List.of("substr(1,2)"), FieldCondition.LT, 3)))
                    .isEqualTo("(substr(\"age\", 1,2) < ?)");
        }
    }

    // endregion

    // region 剩余操作符补全

    @Nested
    class RemainingOperators {

        @Test
        void allComparatorsRender() {
            var cases = List.of(
                    List.of(FieldCondition.NE, "(\"name\" <> ?)"),
                    List.of(FieldCondition.GT, "(\"name\" > ?)"),
                    List.of(FieldCondition.GE, "(\"name\" >= ?)"),
                    List.of(FieldCondition.LT, "(\"name\" < ?)"),
                    List.of(FieldCondition.LE, "(\"name\" <= ?)"),
                    List.of(FieldCondition.NOT_LIKE, "(\"name\" not like ?)"));
            cases.forEach(pair ->
                    assertThat(sql(cond("name", List.of(), (String) pair.get(0), "x")))
                            .isEqualTo((String) pair.get(1)));
        }

        @Test
        void likeMultipleValuesJoined() {
            assertThat(sql(cond("name", List.of(), FieldCondition.LIKE, "a", "b")))
                    .isEqualTo("(\"name\" like ? , ?)");
        }

        @Test
        void intersectBindsViaVars() {
            // 值经 jsonb_build_object 绑定参数传入，jsonpath 用 $q0/$q1 引用
            assertThat(sql(cond("tags", List.of(), FieldCondition.INTERSECT, "a", "b")))
                    .isEqualTo("(jsonb_path_exists(\"tags\", '$[*] ? (@ == $q0 || @ == $q1)', jsonb_build_object('q0', ?, 'q1', ?)))");
        }
    }

    // endregion

    // region 多组数据文件（输入 condition_cases.json -> 输出 带时间戳 json）

    @Nested
    class CaseFileMatrix {

        /**
         * 规范：多组测试数据读 json 数组，按序渲染并写出新 json 数组（后缀时间戳）。
         * 同时校验渲染均产出合法 where 片段。
         */
        @Test
        @SuppressWarnings("unchecked")
        void renderAllCasesAndDumpTimestamped() throws Exception {
            var mapper = FilamentsConfig.DEFAULT_MAPPER;
            var in = getClass().getResourceAsStream("/condition_cases.json");
            var cases = mapper.readValue(in, new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
            });
            var db = using(POSTGRES);
            var out = cases.stream().map(row -> {
                var label = (String) row.get("label");
                var query = (Map<String, Object>) row.get("query");
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("label", label);
                rec.put("sql", filament.build(db, query).getSQL());
                return rec;
            }).toList();
            assertThat(out).hasSize(cases.size());
            assertThat(out).allSatisfy(rec ->
                    assertThat((String) rec.get("sql")).isNotEmpty());

            var stamp = System.currentTimeMillis();
            var outFile = java.nio.file.Path.of("build", "condition_cases-" + stamp + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(outFile.toFile(), out);
            assertThat(java.nio.file.Files.exists(outFile)).isTrue();
        }
    }

    // endregion

    // region 异常路径（负向）

    @Nested
    class Exceptions {

        @Test
        void unknownOperatorRejected() {
            assertThatThrownBy(() -> filament.buildConditionFromFieldCondition(
                    cond("name", List.of(), "UNKNOWN_OP", "x")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unknown operator");
        }

        @Test
        void illegalColumnNameRejected() {
            assertThatThrownBy(() -> filament.buildConditionFromFieldCondition(
                    cond("na me", List.of(), FieldCondition.EQ, "x")))
                    .isInstanceOf(Error.class)
                    .hasMessageContaining("非法字段名");
        }

        @Test
        void illegalJsonPathSegmentRejected() {
            assertThatThrownBy(() -> filament.buildConditionFromFieldCondition(
                    cond("address.a ge", List.of(), FieldCondition.EQ, "x")))
                    .isInstanceOf(Error.class)
                    .hasMessageContaining("非法字段名");
        }

        @Test
        void injectionInColumnNameRejected() {
            assertThatThrownBy(() -> filament.buildConditionFromFieldCondition(
                    cond("x'); drop table y; --", List.of(), FieldCondition.EQ, 1)))
                    .isInstanceOf(Error.class);
        }

        @Test
        void funcNotInWhitelistRejected() {
            assertThatThrownBy(() -> filament.buildConditionFromFieldCondition(
                    cond("name", List.of("pg_sleep(1)"), FieldCondition.EQ, "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("白名单");
        }

        @Test
        void funcNonNumericParamRejected() {
            assertThatThrownBy(() -> filament.buildConditionFromFieldCondition(
                    cond("name", List.of("substr(1,'a')"), FieldCondition.EQ, "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("数字字面量");
        }
    }

    // endregion
}
