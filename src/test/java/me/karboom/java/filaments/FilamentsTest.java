package me.karboom.java.filaments;

import me.karboom.java.filaments.model.User;
import me.karboom.java.filaments.model.UserEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * CRUD 全链路（真库 user_test，schema filaments_test，不 mock）。
 *
 * 说明：doQuery 以 select 字段名映射模型，SELECT * 通道（getByIds / 无 rt 的 get）
 * 不产出可映射列——见最终报告"库现状缺陷"。本类字段级断言统一走带 rt 的查询。
 *
 * <pre>
 * create 入参正交表（数据量 × 字段值形态 × 校验）：
 * ┌──────────┬───────────┬───────────┬───────────┬──────────────┐
 * │ data 数  │ 字段含 null│ 含 jsonb  │ 含 enum   │ 校验          │
 * ├──────────┼───────────┼───────────┼───────────┼──────────────┤
 * │ 1        │ ✓         │ ✓         │ ✓         │ 通过          │
 * │ N        │ ✓         │ ✓         │ ✓         │ 失败→Error    │
 * └──────────┴───────────┴───────────┴───────────┴──────────────┘
 * </pre>
 */
@Timeout(30)
class FilamentsTest {

    static TestFilaments dbFilament;

    @BeforeAll
    static void initDb() {
        TestDb.initTables();
    }

    private TestFilaments f;

    @BeforeEach
    void setUp() {
        TestDb.truncateAll();
        f = new TestFilaments(TestDb.TABLE);
    }

    /** 批量种子：ids 0..count-1 */
    private void seed(int count) {
        var list = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> TestFilaments.seed(String.valueOf(i)))
                .toList();
        f.create(TestDb.db(), list);
    }

    // region create

    @Nested
    class Create {

        @Test
        void singleReturnsAffectedRows() {
            assertThat(f.create(TestDb.db(), TestFilaments.seed("1"))).isEqualTo(1);
        }

        @Test
        void batchInsertsAll() {
            var rows = f.create(TestDb.db(), List.of(TestFilaments.seed("1"), TestFilaments.seed("2")));
            assertThat(rows).isEqualTo(2);
            assertThat(TestDb.db().fetch("select id from filaments_user")).hasSize(2);
        }

        @Test
        void jsonEnumAndDecimalPersisted() {
            f.create(TestDb.db(), TestFilaments.seed("1"));
            var json = TestDb.db().fetch(
                    "select tags, address, type, balance from filaments_user where id = '1'")
                    .get(0);
            assertAll(
                    () -> assertThat(json.get(0).toString()).contains("a"),
                    () -> assertThat(json.get(1).toString()).contains("sh"),
                    () -> assertThat(json.get(2)).isEqualTo("A"), // 枚举按 name() 存
                    () -> assertThat((BigDecimal) json.get(3)).isEqualByComparingTo("12.3456"));
        }

        @Test
        void nullFieldUsesDefault() {
            var u = new User();
            u.id = "n1";
            f.create(TestDb.db(), u);
            var age = TestDb.db().fetchValue("select age from filaments_user where id = 'n1'");
            assertThat(age).isNull();
        }

        @Test
        void validationFailureThrowsError() {
            f.validator = () -> {
                var b = new am.ik.yavi.builder.ValidatorBuilder<User>();
                b.constraint(User::getName, "name", c -> c.greaterThanOrEqual(5));
                return b;
            };
            var bad = TestFilaments.seed("1");
            bad.name = "ab";
            assertThatThrownBy(() -> f.create(TestDb.db(), bad))
                    .isInstanceOf(Error.class)
                    .hasMessageContaining("name");
        }

        @Test
        void beforeHookMutatesData() {
            f.beforeHook = u -> u.wechatId = "set-by-before";
            f.create(TestDb.db(), TestFilaments.seed("1"));
            var wechat = TestDb.db().fetchValue("select wechat_id from filaments_user where id = '1'");
            assertThat(wechat).isEqualTo("set-by-before");
        }
    }

    // endregion

    // region delete

    @Nested
    class Delete {

        @Test
        void byIdRemovesRow() {
            seed(2);
            f.deleteByIds(TestDb.db(), "0");
            assertThat(TestDb.db().fetch("select id from filaments_user where id = '0'")).isEmpty();
        }

        @Test
        void byIdsListRemovesMany() {
            seed(3);
            f.deleteByIds(TestDb.db(), List.of("0", "1"));
            assertThat(TestDb.db().fetch("select id from filaments_user")).hasSize(1);
        }

        @Test
        void nonExistentIdNoThrow() {
            seed(1);
            f.deleteByIds(TestDb.db(), List.of("999"));
            assertThat(TestDb.db().fetch("select id from filaments_user")).hasSize(1);
        }
    }

    // endregion

    // region update

    @Nested
    class Update {

        @Test
        void updatesNonNullFieldsOnly() {
            f.create(TestDb.db(), TestFilaments.seed("1"));
            var patch = new User();
            patch.name = "updated";
            f.updateByIds(TestDb.db(), List.of("1"), patch);
            var row = TestDb.db().fetchOne("select name, age from filaments_user where id = '1'");
            assertAll(
                    () -> assertThat(row.get("name", String.class)).isEqualTo("updated"),
                    () -> assertThat(row.get("age", Integer.class)).isEqualTo(21));
        }

        @Test
        void pkNotIncludedInSet() {
            f.create(TestDb.db(), TestFilaments.seed("1"));
            var patch = new User();
            patch.id = "hijack"; // 主键不参与更新
            patch.name = "x";
            f.updateByIds(TestDb.db(), List.of("1"), patch);
            assertThat(TestDb.db().fetch("select id from filaments_user where id = '1'")).isNotEmpty();
        }

        @Test
        void singleIdOverload() {
            f.create(TestDb.db(), TestFilaments.seed("1"));
            var patch = new User();
            patch.name = "one";
            f.updateByIds(TestDb.db(), "1", patch);
            assertThat(TestDb.db().fetchValue("select name from filaments_user where id = '1'")).isEqualTo("one");
        }

        @Test
        void nonExistentIdsAffectNothing() {
            seed(2);
            var patch = new User();
            patch.name = "z";
            f.updateByIds(TestDb.db(), List.of("999"), patch);
            var still = TestDb.db().fetchValue("select name from filaments_user where id = '0'");
            assertThat(still).isEqualTo("user-0");
        }
    }

    // endregion

    // region getByIds（存在性；字段映射限制见类注释）

    @Nested
    class GetByIds {

        @Test
        void singleIdReturnsRow() {
            seed(2);
            assertThat(f.getByIds(TestDb.db(), "0")).isNotNull();
        }

        @Test
        void missingIdReturnsNull() {
            seed(1);
            assertThat(f.getByIds(TestDb.db(), "999")).isNull();
        }

        @Test
        void idListReturnsMatchingCount() {
            seed(3);
            assertThat(f.getByIds(TestDb.db(), List.of("0", "1"))).hasSize(2);
        }

        @Test
        void lockFlagStillReturnsRow() {
            seed(1);
            assertThat(f.getByIds(TestDb.db(), List.of("0"), true)).hasSize(1);
        }
    }

    // endregion

    // region get（带 rt）

    @Nested
    class Get {

        @Test
        void rtProjectionMapsFields() {
            seed(1);
            var list = f.get(TestDb.db(), Map.of("rt", "id,name,age"));
            var u = list.getFirst();
            assertAll(
                    () -> assertThat(u.id).isEqualTo("0"),
                    () -> assertThat(u.name).isEqualTo("user-0"),
                    () -> assertThat(u.age).isEqualTo(20));
        }

        @Test
        void jsonAndEnumMappedViaRt() {
            seed(1);
            var u = f.get(TestDb.db(), Map.of("rt", "id,name,tags,address,type")).getFirst();
            assertAll(
                    () -> assertThat(u.tags).containsExactly("a", "b"),
                    () -> assertThat(u.type).isEqualTo(UserEnum.A),
                    () -> assertThat(u.address.city).isEqualTo("sh"),
                    () -> assertThat(u.address.age).isEqualTo(10));
        }

        @Test
        void singleRtFieldThrows() {
            assertThatThrownBy(() -> f.get(TestDb.db(), Map.of("rt", "id")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("至少返回两个字段");
        }

        @Test
        void filterByIn() {
            seed(3);
            var ids = f.get(TestDb.db(), Map.of("rt", "id,name", "id|in", "0,2")).stream()
                    .map(u -> u.id).toList();
            assertThat(ids).containsExactlyInAnyOrder("0", "2");
        }

        @Test
        void filterByLike() {
            seed(2);
            var list = f.get(TestDb.db(), Map.of("rt", "id,name", "name|like", "user-%"));
            assertThat(list).hasSize(2);
        }

        @Test
        void orderDesc() {
            seed(3);
            var ids = f.get(TestDb.db(), Map.of("rt", "id,name", "od", "-id")).stream()
                    .map(u -> u.id).toList();
            assertThat(ids).containsExactly("2", "1", "0");
        }

        @Test
        void paginationSlicesAndKeepsOrder() {
            seed(5);
            var page = f.get(TestDb.db(), Map.of("rt", "id,name", "od", "id", "pc", 2, "p", 2));
            assertThat(page.stream().map(u -> u.id).toList()).containsExactly("2", "3");
        }

        @Test
        void conditionViaLgOr() {
            seed(3);
            var list = f.get(TestDb.db(), Map.of(
                    "rt", "id,name",
                    "lg", "!(a,b)",
                    "a::name", "user-0",
                    "b::age|ge", 22));
            var ids = list.stream().map(u -> u.id).toList();
            assertThat(ids).containsExactlyInAnyOrder("0", "2");
        }
    }

    // endregion

    // region getByPages

    @Nested
    class ByPages {

        @Test
        void returnsListAndTotal() {
            seed(5);
            var res = f.getByPages(TestDb.db(), Map.of("rt", "id,name", "od", "id", "pc", 2, "p", 1, "pg", true));
            assertAll(
                    () -> assertThat(res.list).hasSize(2),
                    () -> assertThat(res.total).isEqualTo(5));
        }

        @Test
        void defaultsWhenNoPagination() {
            seed(3);
            var res = f.getByPages(TestDb.db(), Map.of("rt", "id,name"));
            assertAll(
                    () -> assertThat(res.list).hasSize(3),
                    () -> assertThat(res.total).isNull()); // 无 pg 不算 total
        }

        @Test
        void secondPageOffset() {
            seed(5);
            var res = f.getByPages(TestDb.db(), Map.of("rt", "id,name", "od", "id", "pc", 2, "p", 3, "pg", true));
            assertThat(res.list.stream().map(u -> u.id).toList()).containsExactly("4");
        }
    }

    // endregion

    // region aggregation

    @Nested
    class Aggregation {

        @Test
        void countAll() {
            seed(4);
            var res = f.aggregation(TestDb.db(), Map.of("count", "id"), Map.of(), List.of(), null);
            assertThat(res.getFirst().get("countId")).isEqualTo(4L);
        }

        @Test
        void countGroupByField() {
            seed(3);
            var res = f.aggregation(TestDb.db(), Map.of("count", "id"), Map.of(), List.of("type"), null);
            assertAll(
                    () -> assertThat(res).hasSize(1),
                    () -> assertThat(res.getFirst().get("type")).isEqualTo("A"),
                    () -> assertThat(res.getFirst().get("countId")).isEqualTo(3L));
        }

        @Test
        void sumNumeric() {
            seed(2); // age 20 + 21
            var res = f.aggregation(TestDb.db(), Map.of("sum", "age"), Map.of(), List.of(), null);
            assertThat(((Number) res.getFirst().get("sumAge")).longValue()).isEqualTo(41L);
        }

        @Test
        void aggregationWithCondition() {
            seed(5);
            var res = f.aggregation(TestDb.db(), Map.of("count", "id"), Map.of("id|in", "0,1,2"), List.of(), null);
            assertThat(res.getFirst().get("countId")).isEqualTo(3L);
        }

        @Test
        void countDistinctAlias() {
            seed(3);
            var res = f.aggregation(TestDb.db(), Map.of("countDistinct", "type"), Map.of(), List.of(), null);
            assertThat(res.getFirst().get("countDistinctType")).isEqualTo(1L);
        }

        @Test
        void illegalAggFuncRejected() {
            assertThatThrownBy(() -> f.aggregation(TestDb.db(), Map.of("pg_sleep", "id"), Map.of(), List.of(), null))
                    .isInstanceOf(Error.class)
                    .hasMessageContaining("不支持的聚合函数");
        }
    }

    // endregion

    // region 生命周期钩子与 after

    @Nested
    class Hooks {

        @Test
        void afterHookAppliedOnRead() {
            seed(1);
            f.afterHook = u -> {
                u.name = u.name + "-tagged";
                return u;
            };
            var u = f.get(TestDb.db(), Map.of("rt", "id,name")).getFirst();
            assertThat(u.name).endsWith("-tagged");
        }
    }

    // endregion

    // region 自定义主键

    @Nested
    class CustomPk {

        @Test
        void customPkUsedForLookup() {
            var custom = new TestFilaments(TestDb.TABLE, "id");
            custom.create(TestDb.db(), TestFilaments.seed("1"));
            assertThat(custom.getByIds(TestDb.db(), "1")).isNotNull();
        }
    }

    // endregion

}
