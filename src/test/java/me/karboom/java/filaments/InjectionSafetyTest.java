package me.karboom.java.filaments;

import me.karboom.java.filaments.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.SQLDialect.POSTGRES;
import static org.jooq.impl.DSL.using;

/**
 * 防注入（负向）：所有用户可控入口的字段/函数/值都必须被拒或绑定，绝不进 SQL 文本。
 *
 * <pre>
 * 正交表（注入点 × 载荷类型）：
 * ┌────────────────┬──────────┬──────────┬──────────┬─────────────┬──────────────┐
 * │ 注入点          │ 空格      │ 引号/分号 │ 括号      │ 反引号(合法剥离)│ 期望         │
 * ├────────────────┼──────────┼──────────┼──────────┼─────────────┼──────────────┤
 * │ rt 字段         │ ✓        │ ✓        │ ✓        │ ✓           │ Error/剥离    │
 * │ od 排序键       │ ✓        │ ✓        │ ✓        │ ✓           │ Error/剥离    │
 * │ where 字段      │ ✓        │ ✓        │ ✓        │ ✓           │ Error/剥离    │
 * │ jsonb 路径段     │ ✓        │ ✓        │ ✓        │ —           │ Error         │
 * │ 聚合/分组字段    │ ✓        │ ✓        │ ✓        │ —           │ Error         │
 * │ 函数链名/参数    │ —        │ —        │ ✓        │ —           │ IllegalArg    │
 * │ 条件值(任意)     │ ✓        │ ✓        │ ✓        │ ✓           │ 绑定不进文本  │
 * └────────────────┴──────────┴──────────┴──────────┴─────────────┴──────────────┘
 * </pre>
 */
@Timeout(5)
class InjectionSafetyTest {

    static final List<String> PAYLOADS = List.of(
            "a b", "x;drop", "e'vil", "1)--", "u\"n\"i");

    TestFilaments filament;

    @BeforeEach
    void setUp() {
        filament = new TestFilaments(TestDb.TABLE);
    }

    // region identsSafe（静态，字段与 jsonb 路径）

    @Nested
    class IdentsSafe {

        @Test
        void acceptsValidDotPath() {
            assertThat(Filaments.identsSafe("address.city")).containsExactly("address", "city");
        }

        @Test
        void rejectsEveryPayload() {
            PAYLOADS.forEach(p ->
                    assertThatThrownBy(() -> Filaments.identsSafe(p))
                            .as("payload=%s", p)
                            .isInstanceOf(Error.class)
                            .hasMessageContaining("非法字段名"));
        }

        @Test
        void rejectsInDotSegment() {
            assertThatThrownBy(() -> Filaments.identsSafe("address.a b"))
                    .isInstanceOf(Error.class);
        }

        @Test
        void rejectsDigitLeading() {
            assertThatThrownBy(() -> Filaments.identsSafe("1bad"))
                    .isInstanceOf(Error.class);
        }
    }

    // endregion

    // region 字段名在各查询入口被拒

    @Nested
    class FieldEntryPoints {

        @Test
        void whereFieldPayloadRejected() {
            PAYLOADS.forEach(p ->
                    assertThatThrownBy(() -> filament.build(using(POSTGRES), Map.of(p + "|eq", 1)))
                            .as("where payload=%s", p)
                            .isInstanceOf(RuntimeException.class));
        }

        @Test
        void orderKeyPayloadRejected() {
            PAYLOADS.forEach(p ->
                    assertThatThrownBy(() -> filament.build(using(POSTGRES), Map.of("od", p)))
                            .as("od payload=%s", p)
                            .isInstanceOf(RuntimeException.class));
        }

        @Test
        void groupFieldPayloadRejected() {
            PAYLOADS.forEach(p ->
                    assertThatThrownBy(() -> filament.buildAgg(using(POSTGRES), Map.of("count", "id"), Map.of(), List.of(p)))
                            .as("group payload=%s", p)
                            .isInstanceOf(RuntimeException.class));
        }

        @Test
        void aggTargetFieldPayloadRejected() {
            assertThatThrownBy(() -> filament.buildAgg(using(POSTGRES), Map.of("count", "x;drop"), Map.of(), List.of()))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void jsonPathSegmentPayloadRejected() {
            assertThatThrownBy(() -> filament.build(using(POSTGRES), Map.of("address." + "a b|eq", 1)))
                    .isInstanceOf(Error.class);
        }
    }

    // endregion

    // region rt 字段：加引号安全包裹，不产生多语句

    @Nested
    class RtField {

        @Test
        void rtInjectWrappedNotSplit() {
            // rt 走 columnRef 的 name() 通道（加引号标识符），载荷成为单个引用标识符，绝不拼成第二条语句
            var sql = filament.build(using(POSTGRES), Map.of("rt", "id,x;drop"))
                    .getSQL();
            assertThat(sql)
                    .contains("\"x;drop\"")
                    .doesNotContain("drop table");
            assertThat(sql.toLowerCase().split("select", -1)).hasSize(2); // 仍只有一个 select
        }
    }

    // endregion

    // region 函数白名单（buildFuncChain 精确匹配 + 参数仅数字）

    @Nested
    class FuncWhitelist {

        @Test
        void arbitraryFuncRejected() {
            assertThatThrownBy(() -> filament.build(using(POSTGRES), Map.of("name|pg_sleep(1)|eq", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("白名单");
        }

        @Test
        void stringParamRejected() {
            assertThatThrownBy(() -> filament.build(using(POSTGRES), Map.of("name|substr(a,2)|eq", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("数字字面量");
        }
    }

    // endregion

    // region 反引号剥离（合法用法保留）

    @Nested
    class Backtick {

        @Test
        void strippedAndAllowed() {
            var sql = filament.build(using(POSTGRES), Map.of("`name`", "x")).getSQL();
            assertThat(sql).contains("\"name\"");
        }
    }

    // endregion

    // region 值永远绑定，不进 SQL 文本

    @Nested
    class ValuesAlwaysBound {

        @Test
        void payloadValueNotInlined() {
            PAYLOADS.forEach(p -> {
                var sql = filament.build(using(POSTGRES), Map.of("name", p)).getSQL();
                assertThat(sql).doesNotContain(p); // 值只以 ? 出现
            });
        }

        @Test
        void intersectValueNotInlined() {
            var sql = filament.build(using(POSTGRES), Map.of("tags|intersect", "a';drop")).getSQL();
            assertThat(sql).doesNotContain("';drop").contains("jsonb_path_exists");
        }
    }

    // endregion
}
