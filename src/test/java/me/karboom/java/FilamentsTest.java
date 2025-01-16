package me.karboom.java;

import am.ik.yavi.builder.ValidatorBuilder;
import am.ik.yavi.core.CustomConstraint;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.jooq.*;
import org.jooq.conf.ParamType;
import org.jooq.exception.DataTypeException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultBinding;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultConverterProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.SQLDialect.SQLITE;

public class FilamentsTest {


    @AllArgsConstructor
    public class EnumConstraint implements CustomConstraint<String> {
        private Class cls;

        @Override
        public String defaultMessageFormat() {
            return "";
        }

        @Override
        public String messageKey() {
            return "string.enum";
        }

        @Override
        public boolean test(String s) {
            try {
                Enum.valueOf(this.cls, s);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    Filaments<User> filaments;
    DSLContext db;
    String table = "user";



    @AllArgsConstructor
    public enum TYPE {
        A("普通"),
        B("中级"),
        C("高级"),
        ;
        public final String desc;
    }



    @SneakyThrows
    @BeforeEach
    void init() {

        ByteBuddyAgent.install();
        var cls = Arrays.stream(DefaultBinding.class.getDeclaredClasses()).toList().get(11);
        new ByteBuddy()
                .redefine(cls)
                .method(ElementMatchers.named("get0"))
                .intercept(FixedValue.value("asd"))
                .make()
                .load(cls.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent());


        String userName = "postgres";
        String password = "test";
        String url = "jdbc:postgresql://127.0.0.1:5432/postgres?stringtype=unspecified";

        var conn = DriverManager.getConnection(url, userName, password);
//        ((org.postgresql.PGConnection)conn).addDataType("geometry","org.postgis.PGgeometry");
//        ((org.postgresql.PGConnection)conn).addDataType("box3d",Class.forName("org.postgis.PGbox3d"));
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(conn);
        configuration.set(SQLDialect.POSTGRES);
//        configuration.set(new JSONConverterProvider());

        db = DSL.using(configuration);

        // 创建表
        db.execute("""
CREATE TABLE if not exists "%s" (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    wechat_id varchar(20) NOT NULL,
    type varchar(1) not null,
    roles JSONB DEFAULT '[]'::jsonb,
    extra JSONB not null DEFAULT '{}'::jsonb,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    location varchar(50) NOT NULL,
    geo_data geometry
);
""".formatted(table));

        // 填充数据
        db.execute("""
INSERT INTO "%s" (id, name, wechat_id, type, roles, extra, create_time, location) VALUES
(1, 'User1', 'xx' , 'A', '[1, 2, 3]', '{"total_count": 10, "age": 30, "email": "user1@example.com"}', DEFAULT, 'point(-71.064544, 42.28787)'),
(2, 'User2', 'xx' , 'B', '[4, 5, 6]', '{"total_count": 10, "age": 25, "email": "user2@example.com"}', DEFAULT, 'point(-71.064544, 42.28787)'),
(3, 'User3', 'xx' , 'C', '[7, 8, 9]', '{"total_count": 10, "age": 35, "email": "user3@example.com"}', DEFAULT, 'point(-71.064544, 42.28787)'),
(4, 'User4', 'xx' , 'A', '[10, 11, 12]', '{"total_count": 10, "age": 28, "email": "user4@example.com"}', DEFAULT, 'point(-71.064544, 42.28787)'),
(5, 'User5', 'xx' , 'B', '[13, 14, 15]', '{"total_count": 10, "age": 40, "email": "user5@example.com"}', DEFAULT, 'point(-71.064544, 42.28787)'),
(6, 'User6', 'xx' , 'C', '[16, 17, 18]', '{"total_count": 10, "age": 22, "email": "user6@example.com"}', DEFAULT, 'point(-71.064544, 42.28787)'),
(7, 'User7', 'xx' , 'A', '[19, 20, 21]', '{"total_count": 10, "age": 33, "email": "user7@example.com"}', DEFAULT, 'point(-71.064544, 42.28787)'),
(8, 'User8', 'xx' , 'B', '[22, 23, 24]', '{"total_count": 10, "age": 27, "email": "user8@example.com"}', DEFAULT, 'point(-71.064544, 42.28787)'),
(9, 'User9', 'xx' , 'C', '[25, 26, 27]', '{"total_count": 10, "age": 31, "email": "user9@example.com"}', DEFAULT, 'point(-71.064544, 42.28787)'),
(10, 'User10' ,'xx' , 'A', '[28, 29, 30]', '{"total_count": 10, "age": 29, "email": "user10@example.com"}', DEFAULT, 'point(-71.064544, 42.28787)');
""".formatted(table));

        var T = table;
        filaments = new Filaments<User>("user") {

            @Override
            public ValidatorBuilder<User> initValidator() {
                return ValidatorBuilder.<User>of()
                        .constraint(User::getId, "id", c -> c.notNull())
                        .constraint(User::getType, "type", c -> c
                                .predicate(new EnumConstraint(TYPE.class)).message("类型错误")
                                .notNull())
                        .constraint(User::getRoles, "roles", c -> c.notNull())
                        .nest(User::getExtra, "extra", b-> b
                                .constraint(User.Extra::getEmail, "email", c -> c.notNull()))
                       ;
            }

            {
                table = T;
            }};
    }

    @SneakyThrows
    @AfterEach
    void destroy() throws SQLException {
        db.dropTable(table).execute();
    }

    @Test
    void parseLogic() {
        var m1 = new Filaments<String>(){
            @Override
            public ValidatorBuilder initValidator() {
                return null;
            }
        };

        var t1 = m1.parseLogic("(!(id|in),name|in)");
        assertThat(t1).isEqualTo(false);
    }

    @Test
    void buildFields() {
        var m1 = new Filaments<String>(){
            @Override
            public ValidatorBuilder initValidator() {
                return null;
            }
        };

        var res0 = m1.buildField(new HashMap<>() {{
            put("a", "3");
        }}, "and");
        assertThat(res0.toString().replace("\"", "")).isEqualTo("(a = '3')");


        // 测试 ge
        var res1 = m1.buildField(new HashMap<>() {{
            put("a|ge", "3");
        }}, "and");
        assertThat(res1.toString().replace("\"", "")).isEqualTo("(a >= '3')");

        // 测试 gt
        var res2 = m1.buildField(new HashMap<>() {{
            put("a|gt", "3");
        }}, "and");
        assertThat(res2.toString().replace("\"", "")).isEqualTo("(a > '3')");

        // 测试 le
        var res3 = m1.buildField(new HashMap<>() {{
            put("a|le", "3");
        }}, "and");
        assertThat(res3.toString().replace("\"", "")).isEqualTo("(a <= '3')");

        // 测试 lt
        var res4 = m1.buildField(new HashMap<>() {{
            put("a|lt", "3");
        }}, "and");
        assertThat(res4.toString().replace("\"", "")).isEqualTo("(a < '3')");

        // 测试 in
        var res5 = m1.buildField(new HashMap<>() {{
            put("a|in", "3,4,5");
        }}, "and");
        assertThat(res5.toString().replace("\"", "")).isEqualTo("(a in ('3' , '4' , '5'))");

        // 测试 notIn
        var res6 = m1.buildField(new HashMap<>() {{
            put("a|notIn", "3,4,5");
        }}, "and");
        assertThat(res6.toString().replace("\"", "")).isEqualTo("(a not in ('3' , '4' , '5'))");

        // 测试 between
        var res7 = m1.buildField(new HashMap<>() {{
            put("a|between", List.of('3', '5'));
        }}, "and");
        assertThat(res7.toString().replace("\"", "")).isEqualTo("(a between ('3' and '5'))");

        // 测试 notBetween
        var res8 = m1.buildField(new HashMap<>() {{
            put("a|notBetween", "3,5");
        }}, "and");
        assertThat(res8.toString().replace("\"", "")).isEqualTo("(a not between ('3' and '5'))");

        // 测试 like
        var res9 = m1.buildField(new HashMap<>() {{
            put("a|like", "test%");
        }}, "and");
        assertThat(res9.toString().replace("\"", "")).isEqualTo("(a like 'test%')");

        // 测试 notLike
        var res10 = m1.buildField(new HashMap<>() {{
            put("a|notLike", "test%");
        }}, "and");
        assertThat(res10.toString().replace("\"", "")).isEqualTo("(a not like 'test%')");

    }

    @Test
    void buildFuncChain(){
        var m1 = new Filaments<String>(){
            @Override
            public ValidatorBuilder initValidator() {
                return null;
            }
        };
        var res1 = m1.buildFuncChain("x", List.of("date", "substr(2,3)"));

        System.out.println(res1);
    }

    @Test
    void buildCondition() {
        var m1 = new Filaments<String>(){
            @Override
            public ValidatorBuilder initValidator() {
                return null;
            }
        };

        var res1 = m1.buildCondition(new HashMap<>(){{
            put("a|in", 1);
            put("a|ge", 1);
            put("b", 2);
            put("c", 3);

            put("lg", "!(a,(b,c,a))");
        }});

        System.out.println(res1.toString());
    }

    @Test
    void aggregation () {
        var res = filaments.aggregation(db, new HashMap<>(){{
            put("count", List.of("id"));
            put("countDistinct", "id");
        }}, new HashMap<>(){{
            put("type", "B");
        }}, List.of("type"), null);

        assertThat(res.get(0).get("type")).isEqualTo("B");

        System.out.println(res);
    }

    @Test
    void create(){
        var ID = IdUtil.getSnowflake().nextIdStr();
        var obj = new User(){{
            setId(ID);
            setType("C");
            setName("名字");
            setWechatId("id");
            setRoles(List.of("1", "2", "3"));
            setExtra(new Extra(){{
                setAge(12);
                setEmail("xxx@sohu.com");
            }});
            setLocation("point(0, 0)");
        }};


        var res = filaments.create(db, List.of(obj));

        assertThat(res).isEqualTo(1);

        var res2 = filaments.getByIds(db, ID);
        assertThat(res2).usingRecursiveComparison(new RecursiveComparisonConfiguration(){{ignoreFields("createTime");}}).isEqualTo(obj);
    }

    @Test
    void getById() {
        var res = filaments.getByIds(db, "1");

        assertThat(res.id).isEqualTo("1");
        assertThat(res.extra.email).isEqualTo("user1@example.com");
        assertThat(res.wechatId).isEqualTo("xx");
    }

    @Test
    void get() {
        var query = new HashMap<String, Object>(){{
            put("rt","name,id");
        }};

        var res = filaments.get(db, query);

        assertThat(res.size()).isEqualTo(10);
        assertThat(res.get(0).extra).isNull();
        assertThat(res.get(1).id).isEqualTo("2");


    }

    @Test
    void pages() {
        var query = new HashMap<String, Object>(){{
            put("rt","name,id");
            put("pc", 1);
            put("pg", 1);
        }};

        var res = filaments.pages(db, query);

        assertThat(res.total).isEqualTo(10);
        assertThat(res.list.size()).isEqualTo(1);
    }

    @Test
    void update() {
        var obj = new User(){{
            setName("N");
        }};

        filaments.updateByIds(db, "1", obj);

        var newObj = filaments.getByIds(db, "1");

        assertThat(newObj.name).isEqualTo("N");
    }
}
