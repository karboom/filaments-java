package me.karboom.java.filaments;

import am.ik.yavi.builder.ValidatorBuilder;
import me.karboom.java.filaments.model.LegacyUser;
import me.karboom.java.filaments.model.UserEnum;
import me.karboom.java.filaments.model.WidenUser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 类型往返：handleJson 写侧 + doQuery 读侧取值阶梯。
 *
 * <pre>
 * 正交表（模型字段类型 × 存储形态 × 取值通道）：
 * ┌──────────────────┬───────────────┬──────────────────────────┐
 * │ 模型类型          │ 列/值          │ 通道                      │
 * ├──────────────────┼───────────────┼──────────────────────────┤
 * │ List/POJO/Map     │ jsonb         │ readValue(text)          │
 * │ OffsetDateTime    │ timestamptz   │ record.get(i, type)      │
 * │ java.util.Date    │ timestamptz   │ 读侧响亮拒绝 Error        │
 * │ Enum              │ varchar       │ name() 写 / convert 读    │
 * │ Long ← int4       │ int           │ convertValue 加宽         │
 * │ BigDecimal        │ numeric       │ 原样 + 精度保持           │
 * │ null 列           │ 任意           │ 跳过，保持模型默认值       │
 * └──────────────────┴───────────────┴──────────────────────────┘
 * </pre>
 */
@Timeout(30)
class TypeMappingTest {

    TestFilaments f;

    @BeforeAll
    static void initDb() {
        TestDb.initTables();
    }

    @BeforeEach
    void setUp() {
        TestDb.truncateAll();
        f = new TestFilaments(TestDb.TABLE);
    }

    // region jsonb 字段

    @Nested
    class JsonFields {

        @Test
        void listAndPojoAndMapRoundTrip() {
            f.create(TestDb.db(), TestFilaments.seed("1"));
            var u = f.get(TestDb.db(), Map.of("rt", "id,name,tags,address,profile")).getFirst();
            assertAll(
                    () -> assertThat(u.tags).containsExactly("a", "b"),
                    () -> assertThat(u.address.city).isEqualTo("sh"),
                    () -> assertThat(u.address.age).isEqualTo(11),
                    () -> assertThat(u.profile).containsEntry("nick", "nick-1"));
        }

        @Test
        void pojoSerializedWithSnakeKeys() {
            f.create(TestDb.db(), TestFilaments.seed("1"));
            var city = TestDb.db()
                    .fetchValue("select address ->> 'city' from filaments_user where id = '1'");
            assertThat(city).isEqualTo("sh");
        }

        @Test
        void nullColumnKeepsModelDefault() {
            var u = TestFilaments.seed("1");
            u.tags = null;
            f.create(TestDb.db(), u);
            var read = f.get(TestDb.db(), Map.of("rt", "id,name,tags")).getFirst();
            assertThat(read.tags).isNull();
        }
    }

    // endregion

    // region 时间与标量

    @Nested
    class ScalarsAndTime {

        @Test
        void offsetDateTimeInstantRoundTrip() {
            f.create(TestDb.db(), TestFilaments.seed("1"));
            var t = f.get(TestDb.db(), Map.of("rt", "id,name,createTime")).getFirst().createTime;
            // timestamptz 按库端时区回读，绝对时刻必须一致
            assertThat(t.toInstant()).isEqualTo(OffsetDateTime.parse("2026-01-01T12:00:00+08:00").toInstant());
        }

        @Test
        void defaultMapperKeepsOffset() {
            // 关键行为：禁用 ADJUST_DATES_TO_CONTEXT_TIME_ZONE 后回读不折算
            var src = OffsetDateTime.parse("2026-01-01T12:00:00+08:00");
            var box = FilamentsConfig.DEFAULT_MAPPER
                    .readValue("{\"t\":\"2026-01-01T12:00:00+08:00\"}", OffsetTsBox.class);
            assertThat(box.t).isEqualTo(src);
        }

        /** 只测 mapper 行为的临时盒 */
        public static class OffsetTsBox {
            public OffsetDateTime t;
        }

        @Test
        void bigDecimalKeepsScale() {
            f.create(TestDb.db(), TestFilaments.seed("1"));
            var b = f.get(TestDb.db(), Map.of("rt", "id,name,balance")).getFirst().balance;
            assertThat(b).isEqualByComparingTo(new BigDecimal("12.3456"));
        }

        @Test
        void enumRoundTripsViaName() {
            f.create(TestDb.db(), TestFilaments.seed("1"));
            TestDb.db().execute("update filaments_user set type = 'B' where id = '1'");
            var u = f.get(TestDb.db(), Map.of("rt", "id,name,type")).getFirst();
            assertThat(u.type).isEqualTo(UserEnum.B);
        }

        @Test
        void numberWideningIntToLong() {
            f.create(TestDb.db(), TestFilaments.seed("1"));
            var widen = new Filaments<WidenUser>(TestDb.TABLE) {
                @Override
                public ValidatorBuilder<WidenUser> initValidator() {
                    return new ValidatorBuilder<>();
                }
            };
            var u = widen.get(TestDb.db(), Map.of("rt", "id,age")).getFirst();
            assertThat(u.age).isEqualTo(21L);
        }

        @Test
        void utilDateModelRejectedOnRead() {
            TestDb.db().execute("insert into filaments_legacy (id, create_time) values ('l1', now())");
            var legacy = new Filaments<LegacyUser>(TestDb.LEGACY_TABLE) {
                @Override
                public ValidatorBuilder<LegacyUser> initValidator() {
                    return new ValidatorBuilder<>();
                }
            };
            assertThatThrownBy(() -> legacy.get(TestDb.db(), Map.of("rt", "id,createTime")))
                    .isInstanceOf(Error.class)
                    .hasMessageContaining("请改用");
        }
    }

    // endregion
}
