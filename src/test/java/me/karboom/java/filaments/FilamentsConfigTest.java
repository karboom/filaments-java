package me.karboom.java.filaments;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.PropertyNamingStrategy;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
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
 * 这四个只是 naming 槽显式绑定的备选；默认 naming 由 mapper 派生，见 DerivedNaming。
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
                    // 派生函数不保证 ==，用行为断言
                    () -> assertThat(config.naming("wechatId")).isEqualTo("wechat_id"));
        }

        @Test
        void cloneLeavesOriginalIntact() {
            var changed = config.clone();
            changed.pageSize = 7L;
            assertAll(
                    () -> assertThat(changed.pageSize).isEqualTo(7),
                    // 原对象一字未动：clone 产出的是整份新副本
                    () -> assertThat(config.pageSize).isEqualTo(20));
        }

        @Test
        void cloneCopiesAllItems() {
            var changed = config.clone();
            changed.naming = FilamentsConfig::camel;
            changed.nameSplitter = "#";
            changed.sqlFuncList = FilamentsConfig.MYSQL_FUNCS;
            assertAll(
                    () -> assertThat(changed.naming("wechat_id")).isEqualTo("wechatId"),
                    () -> assertThat(changed.nameSplitter).isEqualTo("#"),
                    () -> assertThat(changed.sqlFuncList).isSameAs(FilamentsConfig.MYSQL_FUNCS),
                    // 没点到的项照抄默认值
                    () -> assertThat(changed.aliasSplitter).isEqualTo(FilamentsConfig.DEFAULT_ALIAS_SPLITTER),
                    () -> assertThat(changed.page).isEqualTo(1),
                    () -> assertThat(changed.pageSize).isEqualTo(20),
                    () -> assertThat(changed.mapper).isSameAs(FilamentsConfig.DEFAULT_MAPPER));
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
            // 故意用非幂等命名：每跑一次多一个前缀，任何通道重复归一化都会露出 t_t_，
            // 内置 snake/camel/lower/asIs 都是幂等的，测不出这类问题
            var config = new FilamentsConfig();
            config.naming = s -> "t_" + FilamentsConfig.snake(s);
            var filament = new TestFilaments("users", "id", config);
            var sql = filament.build(using(POSTGRES), Map.of("rt", "name,createTime,tags"))
                    .getSQL();
            assertAll(
                    () -> assertThat(sql).contains("\"t_name\"").contains("\"t_create_time\""),
                    // JSON 字段分支补的是类型不是列名，形态必须和普通列一致
                    () -> assertThat(sql).contains("\"t_tags\""),
                    () -> assertThat(sql).doesNotContain("t_t_"));
        }

        @Test
        void prefixedNamingIsConsistentAcrossChannels() {
            // rt / where / od 三条通道必须拼出同一个列名，否则同一张表的读写分裂成两套名字
            var config = new FilamentsConfig();
            config.naming = s -> "t_" + FilamentsConfig.snake(s);
            var filament = new TestFilaments("users", "id", config);
            var sql = filament.build(using(POSTGRES), Map.of(
                            "rt", "name",
                            "name", "x",
                            "od", "-createTime"))
                    .getSQL();
            assertAll(
                    () -> assertThat(sql).contains("select \"t_name\" from").contains("\"t_name\" = ?"),
                    () -> assertThat(sql).contains("order by \"t_create_time\" desc"),
                    () -> assertThat(sql).doesNotContain("t_t_"));
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

    // region 派生命名（读写同源是构造保证，不再靠配对纪律）

    @Nested
    class DerivedNaming {

        /** 键名逐位比对用的样本 POJO：覆盖普通双段驼峰 */
        static class Doc {
            public Integer totalCount;
            public String wechatId;
        }

        private static ObjectMapper mapperWith(PropertyNamingStrategy strategy) {
            return JsonMapper.builder()
                    .propertyNamingStrategy(strategy)
                    .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                    .build();
        }

        @Test
        void derivedNamingMatchesWrittenKeys() {
            // 正交表：策略（SNAKE/LOWER_CAMEL/LOWER）× 字段（totalCount/wechatId），
            // 派生查询键必须与 mapper 实际写出的键逐位重合——F2 类失配的构造性反证
            var doc = new Doc();
            doc.totalCount = 1;
            doc.wechatId = "wx";
            for (var strategy : List.<PropertyNamingStrategy>of(
                    PropertyNamingStrategies.SNAKE_CASE,
                    PropertyNamingStrategies.LOWER_CAMEL_CASE,
                    PropertyNamingStrategies.LOWER_CASE)) {
                var mapper = mapperWith(strategy);
                var naming = FilamentsConfig.namingFrom(mapper);
                var written = mapper.readTree(mapper.writeValueAsString(doc));
                assertAll(
                        () -> assertThat(written.has(naming.apply("totalCount"))).isTrue(),
                        () -> assertThat(written.has(naming.apply("wechatId"))).isTrue());
            }
        }

        @Test
        void noStrategyMapperFallsBackToAsIs() {
            // 未配策略的 mapper getPropertyNamingStrategy 返回 null（Jackson 原样写键），
            // 派生必须对应 asIs，否则查询侧改写了库里不存在的键
            var naming = FilamentsConfig.namingFrom(JsonMapper.builder().build());
            assertAll(
                    () -> assertThat(naming.apply("wechatId")).isEqualTo("wechatId"),
                    () -> assertThat(naming.apply("userID")).isEqualTo("userID"));
        }

        @Test
        void defaultNamingFollowsJacksonSnake() {
            // 默认配置的命名派生自 DEFAULT_MAPPER：常规输入与旧 hutool snake 行为一致，
            // userID 边角以 Jackson 为准（user_id），这正是读写同源的代价与收益
            var config = new FilamentsConfig();
            assertAll(
                    () -> assertThat(config.naming("wechatId")).isEqualTo("wechat_id"),
                    () -> assertThat(config.naming("userID")).isEqualTo("user_id"),
                    () -> assertThat(config.naming("wechat_id")).isEqualTo("wechat_id"));
        }

        @Test
        void mapperSwapReDerivesUnboundNaming() {
            var config = new FilamentsConfig();
            config.mapper = mapperWith(PropertyNamingStrategies.LOWER_CAMEL_CASE);
            assertAll(
                    () -> assertThat(config.naming("totalCount")).isEqualTo("totalCount"),
                    // 下划线输入在 LowerCamel 策略下原样（策略只做正向改写），文档已注明的宽容度代价
                    () -> assertThat(config.naming("total_count")).isEqualTo("total_count"));
        }

        @Test
        void boundNamingSurvivesMapperSwap() {
            // naming 槽显式绑定后换 mapper 不得覆盖用户规则——解耦语义要钉死
            var config = new FilamentsConfig();
            config.naming = FilamentsConfig::lower;
            config.mapper = mapperWith(PropertyNamingStrategies.SNAKE_CASE);
            assertThat(config.naming("wechatId")).isEqualTo("wechatid");
        }

        @Test
        void unbindingRestoresDerivation() {
            // naming 槽的 null 是正式语义（未绑定=按 mapper 派生），置回即解除绑定
            var config = new FilamentsConfig();
            config.naming = FilamentsConfig::lower;
            config.naming = null;
            assertThat(config.naming("wechatId")).isEqualTo("wechat_id");
        }
    }

    // endregion
}
