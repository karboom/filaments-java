// 临时探针（第五轮）：跳过校验这一设计本身的连带问题。用完即删。
package me.karboom.java.filaments;

import am.ik.yavi.builder.ValidatorBuilder;
import me.karboom.java.filaments.model.Address;
import me.karboom.java.filaments.model.User;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;

@Timeout(60)
class Round3ProbeTest {

    static void p(String label, Runnable r) {
        try {
            r.run();
            System.out.println("[OK ] " + label);
        } catch (Throwable e) {
            System.out.println("[THROW] " + label + " -> " + e.getClass().getSimpleName() + ": "
                    + String.valueOf(e.getMessage()).split("\n")[0]);
        }
    }

    /** 常见 ORM 用法：公共字段放基类 */
    public static class BaseRow {
        public String id;
        public String name;
    }

    public static class SubRow extends BaseRow {
        public Integer age;
    }

    public static class SubFilaments extends Filaments<SubRow> {
        SubFilaments(String table) {
            super(table);
        }

        @Override
        public ValidatorBuilder<SubRow> initValidator() {
            var b = new ValidatorBuilder<SubRow>();
            b._string(r -> r.name, "name", c -> c.notBlank());
            return b;
        }
    }

    @BeforeAll
    static void initDb() {
        TestDb.initTables();
        TestDb.truncateAll();
    }

    @Test
    void yaviOwnNullSemantics() {
        // 不经 defaultValidator：yavi 自己怎么处理 null 值上的 notBlank
        var builder = new ValidatorBuilder<User>();
        builder._string(User::getName, "name", c -> c.notBlank());
        var raw = builder.build();

        var u = new User();
        u.name = null;
        var r1 = raw.validate(u);
        System.out.println("yavi 直接校验 name=null -> isValid=" + r1.isValid()
                + (r1.isValid() ? "" : " msg=" + r1.getFirst().message()));

        var u2 = new User();
        u2.name = "";
        var r2 = raw.validate(u2);
        System.out.println("yavi 直接校验 name=\"\"   -> isValid=" + r2.isValid()
                + (r2.isValid() ? "" : " msg=" + r2.getFirst().message()));

        // 库的包装之后
        var f = new TestFilaments("whatever");
        var r3 = f.defaultValidator(builder, u).validate(u);
        System.out.println("defaultValidator 包一层 name=null -> isValid=" + r3.isValid());
        var r4 = f.defaultValidator(builder, u2).validate(u2);
        System.out.println("defaultValidator 包一层 name=\"\"   -> isValid=" + r4.isValid());
    }

    @Test
    void inheritedFields() {
        var db = TestDb.db();
        p("模型有基类字段：构造期字段扫描（决定 insert 列清单）", () -> {
            var f = new SubFilaments(TestDb.TABLE);
            var row = new SubRow();
            row.id = "1";
            row.name = "n1";
            row.age = 30;
            System.out.println("     create 影响行数=" + f.create(db, row));
            System.out.println("     读回 id=" + f.get(db, Map.of("id", "1")).getFirst().id
                    + " name=" + f.get(db, Map.of("id", "1")).getFirst().name);
        });
        p("基类字段上挂约束（modelValue 用 getDeclaredField）", () -> {
            var f = new SubFilaments(TestDb.TABLE);
            var row = new SubRow();
            row.id = "2";
            row.name = null;   // 根值 null：约束被跳过，走不到取值
            f.create(db, row);
            var row2 = new SubRow();
            row2.id = "3";
            row2.name = "";    // 根值非 null：约束执行，取 name 时…
            f.create(db, row2);
        });
        p("基类字段作为查询条件", () -> {
            var f = new SubFilaments(TestDb.TABLE);
            System.out.println("     rows=" + f.get(db, Map.of("name", "n1")).size());
        });
    }

    @Test
    void batchPerRowValidation() {
        var db = TestDb.db();
        var f = new StrictUser();
        f.create(db, TestFilaments.seed("0"));
        p("批量：逐行裁剪约束后，同一批内行为不一致", () -> {
            var good = TestFilaments.seed("1");
            var nullName = TestFilaments.seed("2");
            nullName.name = null;
            var blankName = TestFilaments.seed("3");
            blankName.name = "  ";
            var list = List.of(good, nullName, blankName);
            try {
                System.out.println("     影响行数=" + f.create(db, list));
            } catch (FilamentsError e) {
                System.out.println("     抛错=" + e.getMessage() + "；已写入行数=" + f.get(db, Map.of()).size());
            }
        });
        p("整批全 null 的 jsonb 嵌套约束（address.city notBlank）", () -> {
            var g = new NestedStrict();
            var u = TestFilaments.seed("5");
            u.address = new Address();
            u.address.city = null;
            g.create(db, u);
            System.out.println("     city=null 写入成功，address=" + g.get(db, Map.of("id", "5")).getFirst().address);
        });
    }

    static class StrictUser extends TestFilaments {
        StrictUser() {
            super(TestDb.TABLE);
        }

        @Override
        public ValidatorBuilder<User> initValidator() {
            var b = new ValidatorBuilder<User>();
            b._string(User::getName, "name", c -> c.notBlank());
            return b;
        }
    }

    static class NestedStrict extends TestFilaments {
        NestedStrict() {
            super(TestDb.TABLE);
        }

        @Override
        public ValidatorBuilder<User> initValidator() {
            var b = new ValidatorBuilder<User>();
            b._string(u -> u.address.city, "address.city", c -> c.notBlank());
            return b;
        }
    }
}
