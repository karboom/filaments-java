package me.karboom.java.filaments;

import am.ik.yavi.builder.ValidatorBuilder;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.SneakyThrows;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.name;

/**
 * Jackson 3 + jOOQ 往返链路的行为测试。
 *
 * 库的读写不走 jOOQ 的 Record -> POJO 映射，而是
 *   写：toDbValue(列分类, 值) -> JDBC
 *   读：列级直接映射（doQuery）：jsonb 列走 Jackson、时间列走 jOOQ 转换器、其余直取/convertValue
 * 因此「列类型信息」和「文本内容恰好长得像 JSON」两件事都会在这里出问题：
 *   J1 非标量启发式过宽：varchar 值以 { 或 [ 开头时整行查询失败
 *   J2 rt 投影丢列类型：时间列被 jOOQ 渲染成 "yyyy-MM-dd HH:mm:ss"，Jackson 严格 ISO 解析炸
 *   J3 旧 handleJson 的 classloader 判定把枚举/java.sql.* 当成"自定义类"序列化
 *      （现改为构造期按列声明分类，值嗅探已删除）
 *   J4 命名策略让 jsonb 键名与模型字段错配：现由 FilamentsConfig.naming（路径段）+ config.mapper（序列化）配对管理，
 *      读写与查询路径段同源；本文件固定默认 SNAKE 的行为，各风格见 FilamentsConfigTest
 */
public class JacksonProbeTest {

    public enum ET {A, B, C}

    public static class Extra2 {
        public Number age;
        public String email;
        public Number totalCount;
        public BigDecimal amount;
        public OffsetDateTime at;
        public LocalDateTime local;
    }

    public static class JUser {
        public String id;
        public String name;
        public String wechatId;
        public String type;
        public Extra2 extra;
        public String location;
        public OffsetDateTime createTime;
    }

    public static class EUser {
        public String id;
        public String name;
        public String wechatId;
        public ET type;
        public String location;
    }

    /** 时间列用不同 Java 类型声明，检验 rt 投影的类型补全是否会截断/移时区 */
    public static class DUser {
        public String id;
        public java.util.Date createTime;
    }

    public static class IUser {
        public String id;
        public java.time.Instant createTime;
    }

    public static class LUser {
        public String id;
        public java.time.LocalDateTime createTime;
    }

    static class D extends Filaments<DUser> {
        D() { super("filaments_user"); }
        @Override
        public ValidatorBuilder<DUser> initValidator() { return new ValidatorBuilder<>(); }
    }

    static class I extends Filaments<IUser> {
        I() { super("filaments_user"); }
        @Override
        public ValidatorBuilder<IUser> initValidator() { return new ValidatorBuilder<>(); }
    }

    static class L extends Filaments<LUser> {
        L() { super("filaments_user"); }
        @Override
        public ValidatorBuilder<LUser> initValidator() { return new ValidatorBuilder<>(); }
    }

    /** jsonb 列在模型里声明成 Map（Object 声明已被构造期禁止） */
    public static class ObjUser {
        public String id;
        public java.util.Map<String, Object> extra;
    }

    /** 反例：Object 字段必须被构造期拒绝 */
    public static class BadUser {
        public String id;
        public Object extra;
    }

    static class Bad extends Filaments<BadUser> {
        Bad() { super("filaments_user"); }
        @Override
        public ValidatorBuilder<BadUser> initValidator() { return new ValidatorBuilder<>(); }
    }

    /** jsonb 列在模型里声明成 String（就是要拿原始文本） */
    public static class StrUser {
        public String id;
        public String extra;
    }

    static class J extends Filaments<JUser> {
        J() { super("filaments_user"); }
        @Override
        public ValidatorBuilder<JUser> initValidator() { return new ValidatorBuilder<>(); }
    }

    static class E extends Filaments<EUser> {
        E() { super("filaments_user"); }
        @Override
        public ValidatorBuilder<EUser> initValidator() { return new ValidatorBuilder<>(); }
    }

    static class O extends Filaments<ObjUser> {
        O() { super("filaments_user"); }
        @Override
        public ValidatorBuilder<ObjUser> initValidator() { return new ValidatorBuilder<>(); }
    }

    static class S extends Filaments<StrUser> {
        S() { super("filaments_user"); }
        @Override
        public ValidatorBuilder<StrUser> initValidator() { return new ValidatorBuilder<>(); }
    }

    private final J f = new J();
    private final E fe = new E();

    // 同包测试直探包私有绑定通道
    private static Object bind(Filaments<?> fx, String col, Object value) throws Exception {
        var m = Filaments.class.getDeclaredMethod("handleJson", String.class, Object.class);
        m.setAccessible(true);
        return m.invoke(fx, col, value);
    }
    private final O o = new O();
    private final S s = new S();
    private final D d = new D();
    private final I i = new I();
    private final L l = new L();

    // =====================================================================
    // mapper 层（无库依赖）
    // =====================================================================

    @Nested
    @DisplayName("mapper 层行为")
    class MapperLevel {

        @Test
        @SneakyThrows
        void snakeCaseAppliesToNestedJson() {
            var e = new Extra2();
            e.totalCount = 10;
            assertThat(FilamentsConfig.DEFAULT_MAPPER.writeValueAsString(e))
                    .contains("\"total_count\":10");
        }

        @Test
        @SneakyThrows
        void explicitJsonPropertyWinsOverNamingStrategy() {
            // Jackson 3：显式命名的属性不再被命名策略改写，可用于保住 jsonb 原始键名
            var node = FilamentsConfig.DEFAULT_MAPPER.valueToTree(new Annotated());
            assertThat(node.get("totalCount").asInt()).isEqualTo(1);
            assertThat(node.get("other_count").asInt()).isEqualTo(2);
        }

        @Test
        @SneakyThrows
        void numbersKeepPrecision() {
            // 实测基线：浮点走 Double、整数走 Long、超出 double 范围的科学计数法静默变 Infinity
            var e = FilamentsConfig.DEFAULT_MAPPER.readValue(
                    "{\"total_count\":1.5,\"age\":9223372036854775807}", Extra2.class);
            assertThat(e.totalCount).isInstanceOf(Double.class).isEqualTo(1.5);
            assertThat(e.age).isInstanceOf(Long.class).isEqualTo(9223372036854775807L);
            var big = FilamentsConfig.DEFAULT_MAPPER.readValue("{\"total_count\":1e309}", Extra2.class);
            assertThat(big.totalCount).isEqualTo(Double.POSITIVE_INFINITY);
        }

        @Test
        @SneakyThrows
        void offsetIsPreservedOnRoundTrip() {
            var e = new Extra2();
            e.at = OffsetDateTime.parse("2024-05-01T10:11:12+08:00");
            var back = FilamentsConfig.DEFAULT_MAPPER.readValue(
                    FilamentsConfig.DEFAULT_MAPPER.writeValueAsString(e), Extra2.class);
            assertThat(back.at).isEqualTo(e.at);
        }

        @Test
        @SneakyThrows
        void enumBoundByName() {
            // J3：值嗅探曾把枚举序列化成带引号的 "\"A\""，写进 varchar(1) 直接超长
            assertThat(bind(fe, "type", ET.A)).isEqualTo("A");
        }

        @Test
        @SneakyThrows
        void scalarValuesBoundAsIs() {
            var ts = new java.sql.Timestamp(0L);
            assertThat(bind(f, "id", ts)).isSameAs(ts);
            assertThat(bind(f, "id", new BigDecimal("1.50"))).isEqualTo(new BigDecimal("1.50"));
            assertThat(bind(f, "id", "abc")).isEqualTo("abc");
            assertThat(bind(f, "id", null)).isNull();
            // jOOQ JSON/JSONB 是可绑定值：列声明非 JSON，不得二次序列化变味
            var jb = org.jooq.JSONB.valueOf("{\"a\":1}");
            assertThat(bind(f, "id", jb)).isSameAs(jb);
        }

        @Test
        @SneakyThrows
        void serializationFollowsDeclaredColumn() {
            // 是否序列化由构造期字段分类决定，不看值来历：
            // extra 在 JUser 声明为 POJO → JSON 字段
            var e = new Extra2();
            e.totalCount = 3;
            assertThat((String) bind(f, "extra", e)).contains("\"total_count\":3");
            // 同一容器值挂到非 JSON 列上：原样返回（旧的 handleJson 值嗅探已不存在）
            assertThat(bind(f, "id", "keep-me")).isEqualTo("keep-me");
        }
    }

    public static class Annotated {
        @JsonProperty("totalCount")
        public Number totalCount = 1;
        public Number otherCount = 2;
    }

    // =====================================================================
    // 真库往返
    // =====================================================================

    @Nested
    @DisplayName("真库往返")
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
        }

        private JUser user(String id, String name) {
            var e = new Extra2();
            e.age = 30;
            e.totalCount = 10;
            e.amount = new BigDecimal("12345678901234567890.12345");
            e.at = OffsetDateTime.parse("2024-05-01T10:11:12+08:00");

            var u = new JUser();
            u.id = id;
            u.name = name;
            u.wechatId = "wx_" + id;
            u.type = "A";
            u.location = "point(1, 2)";
            u.extra = e;
            u.createTime = OffsetDateTime.parse("2024-05-0" + id + "T10:11:12+08:00");
            return u;
        }

        // J1：文本列内容以 { / [ 开头，曾经整行查询炸掉
        @Test
        void jsonLikeTextInVarcharSurvives() {
            var users = new ArrayList<JUser>();
            for (var pair : List.of(new String[]{"1", "{\"a\":1}"},
                    new String[]{"2", "[1,2]"}, new String[]{"3", "{不是json"})) {
                users.add(user(pair[0], pair[1]));
            }
            f.create(db, users);

            var res = f.get(db, new HashMap<>());
            assertThat(res).hasSize(3);
            assertThat(res.stream().map(u -> u.name).toList())
                    .containsExactly("{\"a\":1}", "[1,2]", "{不是json");
        }

        // J2：rt 投影时间列（对应被 @Disabled 的 bf7 场景）
        @Test
        void rtProjectionKeepsTimeType() {
            f.create(db, List.of(user("1", "n")));

            var res = f.get(db, Map.of("rt", "id,createTime"));
            assertThat(res.getFirst().createTime)
                    .isEqualTo(OffsetDateTime.parse("2024-05-01T10:11:12+08:00"));
        }

        @Test
        void rtProjectionKeepsJsonbObject() {
            f.create(db, List.of(user("1", "n")));

            var res = f.get(db, Map.of("rt", "id,extra"));
            assertThat(res.getFirst().extra).isNotNull();
            assertThat(res.getFirst().extra.totalCount).isEqualTo(10);
            assertThat(res.getFirst().extra.at).isEqualTo(OffsetDateTime.parse("2024-05-01T10:11:12+08:00"));
        }

        /**
         * 现状固定（不是期望行为）：jsonb 里的浮点数被 Jackson 解析成 DoubleNode，
         * 目标字段是 BigDecimal 时末位精度丢失（select * 与 rt 两条路径都一样）。
         * 库侧一行即可修：mapper 开启 DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS，
         * 但它同时会把 Number 字段的浮点值变成 BigDecimal，属行为变更，待确认。
         */
        @Test
        void jsonbFloatLosesBigDecimalPrecision() {
            f.create(db, List.of(user("1", "n")));

            var stored = new BigDecimal("12345678901234567890.12345");
            assertThat(f.get(db, new HashMap<>()).getFirst().extra.amount)
                    .isEqualTo(BigDecimal.valueOf(stored.doubleValue()));
        }

        @Test
        void selectAllStillWorks() {
            f.create(db, List.of(user("1", "n")));

            var res = f.get(db, new HashMap<>());
            assertThat(res.getFirst().extra.email).isNull();
            assertThat(res.getFirst().extra.age).isEqualTo(30);
            assertThat(res.getFirst().createTime).isNotNull();
            assertThat(res.getFirst().wechatId).isEqualTo("wx_1");
        }

        // J3：枚举模型字段全链路
        @Test
        void enumFieldRoundTrip() {
            var u = new EUser();
            u.id = "1";
            u.name = "e";
            u.wechatId = "wx";
            u.type = ET.B;
            u.location = "point(0, 0)";
            fe.create(db, List.of(u));

            assertThat(db.fetch("select type from filaments_user").get(0).get(0)).isEqualTo("B");
            assertThat(fe.get(db, Map.of("id", "1")).getFirst().type).isEqualTo(ET.B);
            assertThat(fe.get(db, Map.of("type", "B")).getFirst().type).isEqualTo(ET.B);
            // rt 投影 + 枚举：枚举不按 JSON 还原，文本直接进枚举字段
            assertThat(fe.get(db, Map.of("rt", "id,type", "id", "1")).getFirst().type).isEqualTo(ET.B);
        }

        // rt 投影下 java.time 类型都能拿到正确瞬间（Instant 保留时刻，LocalDateTime 按会话时区落地）
        @Test
        void rtProjectionKeepsInstantForJavaTimeTypes() {
            f.create(db, List.of(user("1", "n")));
            var expected = OffsetDateTime.parse("2024-05-01T10:11:12+08:00").toInstant();

            assertThat(i.get(db, Map.of("rt", "id,createTime", "id", "1")).getFirst().createTime)
                    .isEqualTo(expected);
            assertThat(l.get(db, Map.of("rt", "id,createTime", "id", "1")).getFirst().createTime)
                    .isEqualTo(java.time.LocalDateTime.parse("2024-05-01T10:11:12"));
        }

        /**
         * java.util.Date 与 timestamptz 天生带不了 offset 语义，读侧映射刻意对它响亮报错，
         * 而不是按某个时区静默折算，模型请改用 OffsetDateTime / Instant。
         */
        @Test
        void utilDateOverTimestamptzFailsLoudly() {
            f.create(db, List.of(user("1", "n")));

            assertThatThrownBy(() -> d.get(db, Map.of("rt", "id,createTime", "id", "1")))
                    .isInstanceOf(me.karboom.java.filaments.Error.class)
                    .hasMessageContaining("OffsetDateTime");
        }

        // jsonb 声明成 Map<String,Object>：按 JSON 还原成 Map
        @Test
        void mapFieldParsedAsJson() {
            f.create(db, List.of(user("1", "n")));

            var res = o.get(db, Map.of("rt", "id,extra", "id", "1"));
            assertThat(res.getFirst().extra).isInstanceOf(Map.class);
            assertThat(res.getFirst().extra).containsEntry("total_count", 10);
        }

        // Object 声明的字段在构造期就被拒绝（存成标量还是 JSON 说不清）
        @Test
        void objectDeclaredFieldRejectedAtConstruction() {
            assertThatThrownBy(Bad::new)
                    .isInstanceOf(me.karboom.java.filaments.Error.class)
                    .hasMessageContaining("Object");
        }

        // 模型把 jsonb 声明成 String（就是要拿原始文本）：以前会被误当 JSON 解析而炸，现在原样返回
        @Test
        void stringFieldKeepsRawJsonText() {
            f.create(db, List.of(user("1", "n")));

            var res = s.get(db, Map.of("rt", "id,extra", "id", "1"));
            assertThat(res.getFirst().extra).startsWith("{").contains("\"total_count\"");
        }

        // 默认 SNAKE 下，库里已有的驼峰 jsonb 键（别的系统写的）仍被静默忽略；
        // 需要读这类数据时给该表配 naming=asIs/camel 的实例（列名解析随之改变）（见 FilamentsConfigTest）
        @Test
        void camelCaseJsonKeyIsIgnored() {
            f.create(db, List.of(user("1", "n")));
            db.execute("update filaments_user set extra = '{\"totalCount\": 77}'::jsonb");

            var res = f.get(db, Map.of("id", "1"));
            assertThat(res.getFirst().extra.totalCount).isNull();
            // 而查询路径必须用下划线写法才命中（同源于 J4）
            assertThat(f.get(db, Map.of("extra.total_count", "77"))).isEmpty();
        }
    }
}
