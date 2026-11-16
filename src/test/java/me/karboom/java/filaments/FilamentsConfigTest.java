package me.karboom.java.filaments;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.jooq.SQLDialect.POSTGRES;
import static org.jooq.impl.DSL.using;

/**
 * FilamentsConfig：命名函数正交 + 默认值约定。
 *
 * <pre>
 * 正交表（命名函数 × 输入风格）：
 * ┌────────────┬───────────┬───────────┬─────────────┬─────────┬─────────┐
 * │ 输入 \ 函数 │ snake     │ camel     │ lower       │ asIs    │         │
 * ├────────────┼───────────┼───────────┼─────────────┼─────────┼─────────┤
 * │ wechatId   │ wechat_id │ wechatId  │ wechatid    │ wechatId│         │
 * │ wechat_id  │ wechat_id │ wechatId  │ wechat_id   │ wechat_id│        │
 * │ userID     │ user_id   │ userID    │ userid      │ userID  │         │
 * │ USER       │ user      │ USER      │ user        │ USER    │         │
 * │ create_time│ create_time│createTime│ create_time │ create_time│      │
 * │ name       │ name      │ name      │ name        │ name    │         │
 * └────────────┴───────────┴───────────┴─────────────┴─────────┴─────────┘
 * 注：snake/lower/asIs 对已是目标风格的输入幂等；camel 遵循 hutool 语义（连续大写段不降格）。
 * </pre>
 */
@Timeout(5)
class FilamentsConfigTest {

    static final UnaryOperator<String> SNAKE = FilamentsConfig::snake;
    static final UnaryOperator<String> CAMEL = FilamentsConfig::camel;
    static final UnaryOperator<String> LOWER = FilamentsConfig::lower;
    static final UnaryOperator<String> AS_IS = FilamentsConfig::asIs;

    // region 四个命名备选函数 × 输入风格

    @Nested
    class Naming {

        @Test
        void snakeCases() {
            assertAll(
                    () -> assertThat(SNAKE.apply("wechatId")).isEqualTo("wechat_id"),
                    () -> assertThat(SNAKE.apply("wechat_id")).isEqualTo("wechat_id"),
                    () -> assertThat(SNAKE.apply("userID")).isEqualTo("user_id"),
                    () -> assertThat(SNAKE.apply("USER")).isEqualTo("user"),
                    () -> assertThat(SNAKE.apply("createTime")).isEqualTo("create_time"),
                    () -> assertThat(SNAKE.apply("name")).isEqualTo("name"));
        }

        @Test
        void camelCases() {
            assertAll(
                    () -> assertThat(CAMEL.apply("wechat_id")).isEqualTo("wechatId"),
                    () -> assertThat(CAMEL.apply("wechatId")).isEqualTo("wechatId"),
                    () -> assertThat(CAMEL.apply("create_time")).isEqualTo("createTime"),
                    // hutool 语义：无下划线时原样保留（连续大写不降格）
                    () -> assertThat(CAMEL.apply("userID")).isEqualTo("userID"),
                    () -> assertThat(CAMEL.apply("USER")).isEqualTo("USER"),
                    () -> assertThat(CAMEL.apply("name")).isEqualTo("name"));
        }

        @Test
        void lowerCases() {
            assertAll(
                    () -> assertThat(LOWER.apply("wechatId")).isEqualTo("wechatid"),
                    () -> assertThat(LOWER.apply("USER")).isEqualTo("user"),
                    () -> assertThat(LOWER.apply("wechat_id")).isEqualTo("wechat_id"));
        }

        @Test
        void asIsNeverTouches() {
            assertAll(
                    () -> assertThat(AS_IS.apply("wechatId")).isEqualTo("wechatId"),
                    () -> assertThat(AS_IS.apply("USER")).isEqualTo("USER"),
                    () -> assertThat(AS_IS.apply("a_b")).isEqualTo("a_b"));
        }

        @Test
        void idempotencyOfTargetStyle() {
            // snake / lower / asIs 对已是目标风格的输入幂等
            assertAll(
                    () -> assertThat(SNAKE.apply(SNAKE.apply("createTime"))).isEqualTo(SNAKE.apply("createTime")),
                    () -> assertThat(LOWER.apply(LOWER.apply("wechatId"))).isEqualTo(LOWER.apply("wechatId")),
                    () -> assertThat(AS_IS.apply(AS_IS.apply("wechatId"))).isEqualTo("wechatId"));
        }
    }

    // endregion

    // region 默认值与实例独立性

    @Nested
    class Defaults {

        FilamentsConfig config;

        @BeforeEach
        void setUp() {
            config = new FilamentsConfig();
        }

        @Test
        void defaultValues() {
            assertAll(
                    () -> assertThat(config.nameSplitter).isEqualTo("|"),
                    () -> assertThat(config.aliasSplitter).isEqualTo("::"),
                    () -> assertThat(config.page).isEqualTo(1),
                    () -> assertThat(config.pageSize).isEqualTo(20),
                    () -> assertThat(config.sqlFuncList).isSameAs(FilamentsConfig.PG_FUNCS),
                    () -> assertThat(config.mapper).isSameAs(FilamentsConfig.DEFAULT_MAPPER),
                    // 方法引用实例不保证 ==，用行为断言
                    () -> assertThat(config.naming.apply("wechatId")).isEqualTo("wechat_id"));
        }

        @Test
        void instancesAreIndependent() {
            var other = new FilamentsConfig();
            config.pageSize = 7L;
            assertAll(
                    () -> assertThat(config.pageSize).isEqualTo(7),
                    () -> assertThat(other.pageSize).isEqualTo(20));
        }

        @Test
        void funcPresetsAreDistinctLists() {
            assertAll(
                    () -> assertThat(FilamentsConfig.MYSQL_FUNCS).contains("group_concat"),
                    () -> assertThat(FilamentsConfig.SQLSERVER_FUNCS).contains("isjson"),
                    () -> assertThat(FilamentsConfig.PG_FUNCS).doesNotContain("group_concat"));
        }
    }

    // endregion

    // region 自定义 naming 影响渲染

    @Nested
    class CustomNaming {

        @Test
        void prefixedNamingAppliesToColumns() {
            var config = new FilamentsConfig();
            config.naming = s -> "t_" + FilamentsConfig.snake(s);
            var filament = new TestFilaments("users", "id", config);
            var sql = filament.build(using(POSTGRES), Map.of("rt", "name,createTime"))
                    .getSQL();
            // rt 通道 naming 被应用两次（见缺陷报告）：t_ 前缀 + snake(create_time) 再加前缀
            assertThat(sql).contains("\"t_t_name\"").contains("\"t_t_create_time\"");
        }

        @Test
        void camelNamingAppliesToColumns() {
            var config = new FilamentsConfig();
            config.naming = FilamentsConfig::camel;
            var filament = new TestFilaments("users", "id", config);
            var sql = filament.build(using(POSTGRES), Map.of("rt", "wechatId,createTime"))
                    .getSQL();
            assertThat(sql).contains("\"wechatId\"").contains("\"createTime\"");
        }
    }

    // endregion
}
