package me.karboom.java;

import am.ik.yavi.builder.ValidatorBuilder;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.jooq.*;
import org.jooq.exception.DataTypeException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultBinding;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultConverterProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class FilamentsTest {
    class JSONConverterProvider implements ConverterProvider {
        final ConverterProvider delegate = new DefaultConverterProvider();
        final ObjectMapper mapper = new ObjectMapper();

        @Override
        public <T, U> Converter<T, U> provide(Class<T> tType, Class<U> uType) {

            // Our specialised implementation can convert from JSON (optionally, add JSONB, too)
            if (tType == JSON.class) {
                return Converter.ofNullable(tType, uType,
                        t -> {
                            try {
                                return mapper.readValue(((JSON) t).data(), uType);
                            }
                            catch (Exception e) {
                                throw new DataTypeException("JSON mapping error", e);
                            }
                        },
                        u -> {
                            try {
                                return (T) JSON.valueOf(mapper.writeValueAsString(u));
                            }
                            catch (Exception e) {
                                throw new DataTypeException("JSON mapping error", e);
                            }
                        }
                );
            }

            // Delegate all other type pairs to jOOQ's default
            else
                return delegate.provide(tType, uType);
        }
    }

    Filaments<User> filaments;
    DSLContext db;
    String table = "user";

    @SneakyThrows
    @BeforeEach
    void init() {

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
    location varchar(50) NOT NULL
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
        filaments = new Filaments<User>() {
            @Override
            public ValidatorBuilder initValidator() {
                return ValidatorBuilder.<User>of()
                        .constraint(User::getId, "id", c -> c.notNull())
                        .constraint(User::getType, "type", c -> c.notNull())
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
        var res1 = m1.buildField(new HashMap<>(){{
            put("a|in", "3");
            put("b|in", 3);
            put("c|in", "1,2");
            put("d|in", List.of("x"));
        }}, "and");
        System.out.println(res1);
//        var t1 = filaments.buildField(con, new HashMap<>(), "");

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

        assertThat(res.getValue(0, "type")).isEqualTo("B");

        System.out.println(res);
    }

    @Test
    void create(){
        var obj = new User(){{
            setId(IdUtil.getSnowflake().nextIdStr());
            setType("B");
            setName("名字");
            setRoles(List.of("1", "2", "3"));
            setExtra(new Extra(){{
                setAge(12);
                setEmail("xxx@sohu.com");
            }});
            setLocation("point(0, 0)");
        }};


        var res = filaments.create(db, List.of(obj));

        assertThat(res).isEqualTo(1);
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
    void update() {
        var obj = new User(){{
            setName("N");
        }};

        filaments.updateByIds(db, "1", obj);

        var newObj = filaments.getByIds(db, "1");

        assertThat(newObj.name).isEqualTo("N");
    }
}
