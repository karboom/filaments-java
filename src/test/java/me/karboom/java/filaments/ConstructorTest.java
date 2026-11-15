package me.karboom.java.filaments;

import am.ik.yavi.builder.ValidatorBuilder;
import me.karboom.java.filaments.model.Address;
import me.karboom.java.filaments.model.BadObjectUser;
import me.karboom.java.filaments.model.UserEnum;
import me.karboom.java.filaments.model.WithStatic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 构造函数 / isJsonType：模型字段扫描与 JSON 分类。
 *
 * <pre>
 * 正交表（字段类型 -> 期望分类）：
 * ┌──────────────────┬────────────────────────┬────────────┬──────────────┐
 * │ 因子：字段类型    │ 取值示例               │ isJsonType │ jsonFields   │
 * ├──────────────────┼────────────────────────┼────────────┼──────────────┤
 * │ String/包装数字   │ name / Integer / BigDecimal │ false  │ 否           │
 * │ 枚举              │ UserEnum               │ false      │ 否           │
 * │ java.time        │ OffsetDateTime/LocalDate │ false     │ 否           │
 * │ util/sql 日期     │ java.util.Date/Timestamp │ false     │ 否（读侧另拒）│
 * │ jOOQ JSON/JSONB  │ JSON / JSONB           │ false      │ 否（防二次序列化）│
 * │ UUID / byte[]    │ UUID / byte[]          │ false      │ 否           │
 * │ POJO             │ Address                │ true       │ 是           │
 * │ List / Map       │ List<String>           │ true       │ 是           │
 * │ 数组             │ String[]               │ true       │ 是           │
 * │ 基本类型 int      │ int                    │ true(现状) │ 模型应使用封装类 │
 * │ static 字段      │ MODEL_TAG              │ 跳过       │ 跳过         │
 * │ Object 字段      │ extra                  │ —          │ 构造期抛 Error │
 * └──────────────────┴────────────────────────┴────────────┴──────────────┘
 * 异常路径：Object 声明字段 -> Error("声明为 Object")
 * </pre>
 */
@Timeout(5)
class ConstructorTest {

    // region isJsonType 正交

    @Nested
    class IsJsonTypeTest {

        @Test
        void scalarsAreNotJson() {
            var notJson = List.of(String.class, Boolean.class, Character.class, Byte.class,
                    Short.class, Integer.class, Long.class, Float.class, Double.class,
                    BigDecimal.class, java.math.BigInteger.class, byte[].class, UUID.class,
                    LocalDate.class, OffsetDateTime.class, java.time.Instant.class,
                    java.util.Date.class, java.sql.Timestamp.class, java.sql.Time.class, java.sql.Date.class,
                    org.jooq.JSON.class, org.jooq.JSONB.class, UserEnum.class);
            assertThat(notJson).noneMatch(Filaments::isJsonType);
        }

        @Test
        void structuredTypesAreJson() {
            assertThat(Filaments.isJsonType(Address.class)).isTrue();
            assertThat(Filaments.isJsonType(List.class)).isTrue();
            assertThat(Filaments.isJsonType(Map.class)).isTrue();
            assertThat(Filaments.isJsonType(String[].class)).isTrue();
        }

        @Test
        void primitiveCountsAsJsonForNow() {
            // 现状记录：基本类型不在标量白名单里，被判 JSON——模型字段应使用封装类（文档约定）
            assertThat(Filaments.isJsonType(int.class)).isTrue();
        }
    }

    // endregion

    // region 模型字段扫描

    @Nested
    class ModelFieldScan {

        TestFilaments filament;

        @BeforeEach
        void setUp() {
            filament = new TestFilaments(TestDb.TABLE);
        }

        @SuppressWarnings("unchecked")
        private <V> V readPrivate(String fieldName) throws Exception {
            var rf = Filaments.class.getDeclaredField(fieldName);
            rf.setAccessible(true);
            return (V) rf.get(filament);
        }

        @Test
        void jsonFieldsClassifiedAtConstruction() throws Exception {
            Set<String> json = readPrivate("jsonFields");
            assertThat(json).containsExactlyInAnyOrder("tags", "address", "profile");
        }

        @Test
        void staticFieldsAreSkipped() throws Exception {
            var sf = new Filaments<WithStatic>("whatever") {
                @Override
                public ValidatorBuilder<WithStatic> initValidator() {
                    return new ValidatorBuilder<>();
                }
            };
            var rf = Filaments.class.getDeclaredField("modelFields");
            rf.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Field> indexed = (Map<String, Field>) rf.get(sf);
            assertThat(indexed).containsKeys("id", "name", "tags");
            assertThat(indexed).doesNotContainKey("model_tag");
            assertThat(indexed).doesNotContainKey("MODEL_TAG");
        }

        @Test
        void objectFieldRejectedAtConstruction() {
            assertThatThrownBy(() -> new Filaments<BadObjectUser>("whatever") {
                @Override
                public ValidatorBuilder<BadObjectUser> initValidator() {
                    return new ValidatorBuilder<>();
                }
            }).isInstanceOf(Error.class)
                    .hasMessageContaining("声明为 Object");
        }

        @Test
        void pkAndConfigDefaults() {
            assertThat(filament.pk).isEqualTo("id");
            assertThat(new TestFilaments(TestDb.TABLE, "uid").pk).isEqualTo("uid");
            assertThat(filament.config.sqlFuncList).isSameAs(FilamentsConfig.PG_FUNCS);
            assertThat(filament.specBuilder).isNotNull();
        }

        @Test
        void configsAreNotSharedBetweenInstances() {
            var a = new TestFilaments(TestDb.TABLE);
            var b = new TestFilaments(TestDb.TABLE);
            a.config.pageSize = 5L;
            assertThat(b.config.pageSize).isEqualTo(20L);
        }
    }

    // endregion
}
