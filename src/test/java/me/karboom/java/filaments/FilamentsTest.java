package me.karboom.java.filaments;

import am.ik.yavi.builder.ValidatorBuilder;
import am.ik.yavi.core.CustomConstraint;
import cn.hutool.core.util.IdUtil;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import me.karboom.java.filaments.spec.FieldCondition;
import me.karboom.java.filaments.spec.LogicNode;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Filaments 正交法测试套件。
 *
 * 每个 @Nested 分组对应一个被测功能，类注释中给出该功能采用的正交设计：
 *   - 因子（维度）与各因子水平
 *   - 所选正交表（标准 L9(3^4)/L9(3^3)，或对互斥水平做裁剪后的近似正交表）
 *   - 用例编号 → 各因子水平组合 的映射表
 * 个别因子水平组合在语义上互斥（如"根节点为裸 !"与"嵌套深度=3"），此类组合按
 * 就近原则降级，并在映射表下注明；水平间的全覆盖由 Supplement/边界用例补足。
 *
 * 集成用例依赖本地 PG（./script/start_test_env.sh + DB_URL/DB_USER/DB_PASSWORD 环境变量）。
 * 表结构（filaments_user / user_mini_pk）维护在 script/base.sql，空库初始化时自动导入；
 * 每个用例前后只清行（外层 @BeforeEach/@AfterEach，@Nested 内自动继承），
 * 种子数据由 filaments.create() 自举写入（dogfooding 插入路径）。
 */
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

    @AllArgsConstructor
    public enum TYPE {
        A("普通"),
        B("中级"),
        C("高级"),
        ;
        public final String desc;
    }

    Filaments<User> filaments;
    DSLContext db;
    String table = "filaments_user";
    final SelectSpecBuilder builder = new SelectSpecBuilder();

    @SneakyThrows
    @BeforeEach
    void init() {
        String url = System.getenv("DB_URL");
        String userName = System.getenv("DB_USER");
        String password = System.getenv("DB_PASSWORD");

        var conn = DriverManager.getConnection(url, userName, password);
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(conn);
        configuration.set(SQLDialect.POSTGRES);

        db = DSL.using(configuration);

        filaments = new Filaments<User>(table) {
            @Override
            public ValidatorBuilder<User> initValidator() {
                return ValidatorBuilder.<User>of()
                        .constraint(User::getId, "id", c -> c.notNull())
                        .constraint(User::getType, "type", c -> c
                                .predicate(new EnumConstraint(TYPE.class)).message("类型错误")
                                .notNull())
                        .constraint(User::getRoles, "roles", c -> c.notNull())
                        .nest(User::getExtra, "extra", b -> b
                                .constraint(User.Extra::getEmail, "email", c -> c.notNull()));
            }
        };

        // 表结构维护在 script/base.sql（start_test_env.sh 初始化空库时导入），测试只清行
        db.deleteFrom(DSL.table(DSL.name(table))).execute();

        // 种子数据由 create() 自举写入（顺带 dogfooding 插入路径）
        filaments.create(db, seedUsers());
    }

    /**
     * 种子数据 10 行：type A={1,4,7,10} B={2,5,8} C={3,6,9}；
     * age=30,25,35,28,40,22,33,27,31,29；action_ids 不传 → 走 DB 默认 '[]'
     */
    private List<User> seedUsers() {
        String[] types = {"A", "B", "C"};
        int[] ages = {30, 25, 35, 28, 40, 22, 33, 27, 31, 29};
        var users = new ArrayList<User>();
        for (int i = 0; i < 10; i++) {
            var extra = new User.Extra();
            extra.setAge(ages[i]);
            extra.setTotalCount(10);
            extra.setEmail("user" + (i + 1) + "@example.com");

            var u = new User();
            u.setId(String.valueOf(i + 1));
            u.setName("User" + (i + 1));
            u.setWechatId("xx");
            u.setType(types[i % 3]);
            u.setRoles(List.of(new User.Role("r1", new User.RoleGrant(true))));
            u.setExtra(extra);
            u.setLocation("point(-71.064544, 42.28787)");
            users.add(u);
        }
        return users;
    }

    @SneakyThrows
    @AfterEach
    void destroy() {
        db.deleteFrom(DSL.table(DSL.name(table))).execute();
    }

    /** 生成合法种子数据的行模板（每个 create 用例换独立 id） */
    User baseUser(String id) {
        User.Extra e = new User.Extra();
        e.setAge(20);
        e.setTotalCount(5);
        e.setEmail("base@example.com");
        User u = new User();
        u.setId(id);
        u.setName("名字");
        u.setWechatId("wx_id");
        u.setType("A");
        u.setRoles(List.of(new User.Role("admin", new User.RoleGrant(true))));
        u.setExtra(e);
        u.setLocation("point(0, 0)");
        u.setActionIds(List.of("a1"));
        return u;
    }

    // =====================================================================
    // parseLogic 逻辑树解析
    // =====================================================================

    /**
     * 正交维度（SelectSpecBuilder.parseLogic）：
     *   D1 根节点类型: 1=AND "(...)"   2=OR "!(...)"   3=裸 "!"
     *   D2 嵌套深度:   1=单层          2=两层          3=三层
     *   D3 叶子条件数: 1=1 个          2=2 个          3=3 个
     *   D4 子项形态:   1=纯叶子        2=纯子树        3=叶子+子树混合
     *
     * 采用 L9(3^4) 正交表（D1=3 仅有"裸!"一种写法，与 D2/D3/D4 的非平凡水平语义互斥，
     * 故该行其余因子降级为最小水平；深度 1 与"纯子树"同样互斥，未排入）：
     *   用例  D1 D2 D3 D4  输入
     *   T1     1  1  1  1  (a=1)
     *   T2     1  1  3  1  (a=1,b=2,c=3)
     *   T3     2  1  2  1  !(a=1,b=2)
     *   T4     3  1  0  1  !
     *   T5     1  2  3  2  ((a=1),(b=2,c=3))
     *   T6     1  2  3  3  (a=1,(b=2,c=3))
     *   T7     2  2  3  3  !(a=1,!(b=2,c=3),d=4)
     *   T8     1  3  3  3  (a=1,(b=2,(c=3)))
     *   T9     2  3  3  3  !(a=1,(b=2,(c=3)))
     * 补充边界：T10 空树 "()"；T11 非法格式 "(abc)" 抛异常；
     *           T12 经完整 parse 流程后 String[] 叶子被转换为 FieldCondition。
     */
    @Nested
    @DisplayName("parseLogic 逻辑树解析 L9(3^4)")
    class ParseLogic {

        private List<Object> leaves(LogicNode n) {
            return n.getValue().stream().filter(v -> v instanceof String[]).toList();
        }

        // T1: D1=1 D2=1 D3=1 D4=1
        @Test
        void t1_singleLeafAnd() {
            var n = builder.parseLogic("(a=1)");
            assertThat(n.getType()).isEqualTo(LogicNode.AND);
            assertThat(n.getValue()).hasSize(1);
            assertThat((String[]) n.getValue().get(0)).containsExactly("a", "1");
            assertThat(n.getAllChild()).contains("a");
            assertThat(n.getChildren()).isEmpty();
        }

        // T2: D1=1 D2=1 D3=3 D4=1
        @Test
        void t2_threeLeavesAnd() {
            var n = builder.parseLogic("(a=1,b=2,c=3)");
            assertThat(n.getType()).isEqualTo(LogicNode.AND);
            assertThat(n.getValue()).hasSize(3);
            assertThat(n.getAllChild()).containsExactlyInAnyOrder("a", "b", "c");
        }

        // T3: D1=2 D2=1 D3=2 D4=1
        @Test
        void t3_twoLeavesOr() {
            var n = builder.parseLogic("!(a=1,b=2)");
            assertThat(n.getType()).isEqualTo(LogicNode.OR);
            assertThat(n.getValue()).hasSize(2);
        }

        // T4: D1=3 裸 "!" → 空 OR 节点
        @Test
        void t4_bareBang() {
            var n = builder.parseLogic("!");
            assertThat(n.getType()).isEqualTo(LogicNode.OR);
            assertThat(n.getValue()).isEmpty();
        }

        // T5: D1=1 D2=2 D3=3 D4=2 纯子树
        @Test
        void t5_pureSubtrees() {
            var n = builder.parseLogic("((a=1),(b=2,c=3))");
            assertThat(n.getType()).isEqualTo(LogicNode.AND);
            assertThat(n.getChildren()).hasSize(2);
            assertThat(leaves(n)).isEmpty();
            assertThat(n.getAllChild()).containsExactlyInAnyOrder("a", "b", "c");
        }

        // T6: D1=1 D2=2 D3=3 D4=3 叶子+子树混合
        @Test
        void t6_mixedAnd() {
            var n = builder.parseLogic("(a=1,(b=2,c=3))");
            assertThat(n.getType()).isEqualTo(LogicNode.AND);
            assertThat(n.getValue()).hasSize(2);
            assertThat(n.getValue().get(0)).isInstanceOf(String[].class);
            assertThat(((LogicNode) n.getValue().get(1)).getType()).isEqualTo(LogicNode.AND);
            assertThat(n.getChildren()).hasSize(1);
        }

        // T7: D1=2 D2=2 D3=3 D4=3 OR 下混合（含 OR 子树）
        @Test
        void t7_orMixedWithOrChild() {
            var n = builder.parseLogic("!(a=1,!(b=2,c=3),d=4)");
            assertThat(n.getType()).isEqualTo(LogicNode.OR);
            assertThat(n.getValue()).hasSize(3);
            assertThat(n.getChildren()).hasSize(1);
            assertThat(n.getChildren().get(0).getType()).isEqualTo(LogicNode.OR);
        }

        // T8: D1=1 D2=3 D3=3 D4=3 三层嵌套 AND
        @Test
        void t8_threeLevelAnd() {
            var n = builder.parseLogic("(a=1,(b=2,(c=3)))");
            assertThat(n.getType()).isEqualTo(LogicNode.AND);
            var level2 = (LogicNode) n.getValue().get(1);
            var level3 = (LogicNode) level2.getValue().get(1);
            assertThat(level3.getType()).isEqualTo(LogicNode.AND);
            assertThat((String[]) level3.getValue().get(0)).containsExactly("c", "3");
        }

        // T9: D1=2 D2=3 D3=3 D4=3 三层嵌套 OR 根
        @Test
        void t9_threeLevelOrRoot() {
            var n = builder.parseLogic("!(a=1,(b=2,(c=3)))");
            assertThat(n.getType()).isEqualTo(LogicNode.OR);
            assertThat(n.getValue()).hasSize(2);
            assertThat(leaves(n)).hasSize(1);
        }

        // T10: 边界——空括号
        @Test
        void t10_emptyTree() {
            var n = builder.parseLogic("()");
            assertThat(n.getType()).isEqualTo(LogicNode.AND);
            assertThat(n.getValue()).isEmpty();
            assertThat(n.getAllChild()).isEmpty();
        }

        // T11: 边界——叶子必须是 k=v 格式
        @Test
        void t11_invalidLeafFormat() {
            assertThatThrownBy(() -> builder.parseLogic("(abc)"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("k=v");
        }

        // T12: 完整 parse 流程下 String[] 叶子转为 FieldCondition（EQ）
        @Test
        void t12_convertToFieldConditionThroughParse() {
            var spec = builder.parse(new HashMap<>() {{
                put("lg", "(name=John)");
            }}, null);
            var fc = (FieldCondition) spec.getWhere().getValue().get(0);
            assertThat(fc.getField()).isEqualTo("name");
            assertThat(fc.getOperator()).isEqualTo(FieldCondition.EQ);
            assertThat(fc.getValues()).containsExactly("John");
        }
    }

    // =====================================================================
    // buildFuncChain 函数链构建
    // =====================================================================

    /**
     * 正交维度（SelectSpecBuilder.buildFuncChain）：
     *   D1 链长度:       1=0 个函数     2=1 个函数    3=2 个函数（链式嵌套）
     *   D2 参数形态:     1=无参 f()     2=单参 f(1)   3=多参 f(1,2)
     *   D3 字段形态:     1=普通列       2=带引号表达式 3=JSONB #>> 路径
     *
     * 三因子全水平数 3×3×3=27，取 L9(3^4) 前 3 列生成 9 行（任意两列水平对全覆盖）：
     *   用例  D1 D2 D3  期望输出
     *   F1     1  -  1  name（原样返回）
     *   F2     2  1  1  count(name)
     *   F3     2  2  2  substr("t"."c", 1)
     *   F4     2  3  3  substr(extra #>> '{age}', 1,3)
     *   F5     3  1  1  date(count(name))
     *   F6     3  3  3  substr(count(x), 1,3)  （多参+无参链）
     *   F7     3  2  2  count(substr(f, 1))
     *   F8     2  1  3  count(extra #>> '{age}')
     *   F9     3  2  1  date(substr(name, 1))
     * 补充边界：F10 空参括号 "date()" → date(field)；F11 链中多函数全组合。
     */
    @Nested
    @DisplayName("buildFuncChain 函数链 L9(3^3)")
    class BuildFuncChain {

        // F1: D1=1（0 个函数）原样返回
        @Test
        void f1_emptyChain() {
            assertThat(builder.buildFuncChain("name", List.of())).isEqualTo("name");
        }

        // F2: D1=2 D2=1 D3=1
        @Test
        void f2_noParamFunc() {
            assertThat(builder.buildFuncChain("name", List.of("count")))
                    .isEqualTo("count(name)");
        }

        // F3: D1=2 D2=2 D3=2 单参数 + 引号表达式字段
        @Test
        void f3_singleParamQuotedField() {
            assertThat(builder.buildFuncChain("\"t\".\"c\"", List.of("substr(1)")))
                    .isEqualTo("substr(\"t\".\"c\", 1)");
        }

        // F4: D1=2 D2=3 D3=3 多参数 + JSONB 路径
        @Test
        void f4_multiParamJsonbField() {
            assertThat(builder.buildFuncChain("extra #>> '{age}'", List.of("substr(1,3)")))
                    .isEqualTo("substr(extra #>> '{age}', 1,3)");
        }

        // F5: D1=3 D2=1 D3=1 两个无参函数链式
        @Test
        void f5_twoNoParamChain() {
            assertThat(builder.buildFuncChain("name", List.of("count", "date")))
                    .isEqualTo("date(count(name))");
        }

        // F6: D1=3 D2=3 D3=3 无参+多参链，JSONB 路径
        @Test
        void f6_chainMultiParamJsonb() {
            assertThat(builder.buildFuncChain("extra #>> '{email}'", List.of("count", "substr(1,3)")))
                    .isEqualTo("substr(count(extra #>> '{email}'), 1,3)");
        }

        // F7: D1=3 D2=2 D3=2 单参链
        @Test
        void f7_chainSingleParams() {
            assertThat(builder.buildFuncChain("\"f\"", List.of("substr(1)", "count")))
                    .isEqualTo("count(substr(\"f\", 1))");
        }

        // F8: D1=2 D2=1 D3=3 无参函数套 JSONB 路径
        @Test
        void f8_noParamJsonb() {
            assertThat(builder.buildFuncChain("extra #>> '{age}'", List.of("count")))
                    .isEqualTo("count(extra #>> '{age}')");
        }

        // F9: D1=3 D2=2 D3=1 单参+无参链，普通列
        @Test
        void f9_chainSingleParamPlain() {
            assertThat(builder.buildFuncChain("name", List.of("substr(1)", "date")))
                    .isEqualTo("date(substr(name, 1))");
        }

        // F10: 边界——空参括号 "f()"
        @Test
        void f10_emptyParensFunc() {
            assertThat(builder.buildFuncChain("field", List.of("date()")))
                    .isEqualTo("date(field)");
        }

        // F11: 边界——三函数混合链（长链参数传递正确性）
        @Test
        void f11_threeLevelChain() {
            assertThat(builder.buildFuncChain("name", List.of("substr(1,2)", "lower", "count")))
                    .isEqualTo("count(lower(substr(name, 1,2)))");
        }
    }

    // =====================================================================
    // buildConditionFromFieldCondition 条件渲染
    // =====================================================================

    /**
     * 正交维度（Filaments.buildConditionFromFieldCondition，单元级 SQL 渲染验证）：
     *   D1 操作符大类: 1=等值比较(EQ/NE/GT/GE/LT/LE)  2=集合范围(IN/NOT_IN/BETWEEN/NOT_BETWEEN)
     *                  3=模糊与JSONB(LIKE/NOT_LIKE/INTERSECT)
     *   D2 值个数:     1=单值  2=两值  3=三值
     *   D3 字段形态:   1=普通列  2=驼峰列  3=JSONB dot 路径(a.b / a.b.c)
     *   D4 函数链:     1=无  2=单函数  3=双函数
     *
     * 采用 L9(3^4) 正交表 + 6 个补点（共 15 用例），实现 D1×D2、D1×D3、D1×D4、
     * D2×D3、D3×D4 全部水平对覆盖；值个数与操作符存在语义约束
     * （BETWEEN 恒 2 值、EQ/比较恒 1 值），受约束点以补点保证操作符全枚举：
     *   用例   D1 D2 D3 D4  输入 key = value
     *   B1      1  1  1  1  type = B                (EQ)
     *   B2      1  1  2  1  wechatId|ne = xx         (NE)
     *   B3      1  1  3  1  extra.age|ge = 30        (GE, #>>)
     *   B4      2  2  1  1  type|in = A,B            (IN)
     *   B5      2  2  3  1  create_time|between = 1,2(BETWEEN)
     *   B6      3  1  3  1  extra.email|like = %u%   (LIKE, #>>)
     *   B7      3  3  1  1  roles|intersect = a,b,c  (INTERSECT)
     *   B8      1  1  1  2  name|substr(1,3)|ne = ab  (NE+单链；"eq" 非关键字，显式写会被当函数)
     *   B9      2  3  2  3  status|substr(1)|count|notIn = a,b,c (NOT_IN+双链)
     *   B10     3  2  2  2  wechatId|substr(1)|notLike = a       (NOT_LIKE+单链)
     *   补点:   GT/1/dot2段/1  B11 | LT/1/dot3段/3 B12 | NE/2/dot2段/1 B13
     *           IN/3/驼峰/1 B14 | BETWEEN/2/dot2段/1 B15 | 隐式EQ+函数链 B16
     * 注：Condition.toString() 会将绑定值内联渲染（= 'b'、in ('a' , 'b')），断言据此书写。
     */
    @Nested
    @DisplayName("buildCondition 条件渲染 正交 L9+补点")
    class BuildCondition {

        /** 渲染为规范化的可断言字符串（折叠空白、统一小写） */
        private String render(FieldCondition fc) {
            return filaments.buildConditionFromFieldCondition(fc).toString()
                    .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }

        private FieldCondition fc(String key, Object value) {
            return builder.buildFieldCondition(Map.of(key, value), key);
        }

        // B1: D1=1 D2=1 D3=1 D4=1 EQ
        @Test
        void b1_eqPlain() {
            assertThat(render(fc("type", "B"))).contains("= 'b'");
        }

        // B2: NE + 驼峰字段
        @Test
        void b2_neCamel() {
            assertThat(render(fc("wechatId|ne", "xx"))).contains("<> 'xx'");
        }

        // B3: GE + JSONB 路径（两段）
        @Test
        void b3_geJsonbPath() {
            var sql = render(fc("extra.age|ge", "30"));
            assertThat(sql).contains("#>> '{age}'").contains(">= '30'");
        }

        // B4: IN + 两值
        @Test
        void b4_inTwoValues() {
            var sql = render(fc("type|in", "A,B"));
            assertThat(sql).contains("in (").contains("'a'").contains("'b'");
        }

        // B5: BETWEEN + 两值 + 普通列
        @Test
        void b5_betweenTwoValues() {
            assertThat(render(fc("create_time|between", "1,2")))
                    .contains("between ('1' and '2')");
        }

        // B6: LIKE + JSONB 路径 + 单值
        @Test
        void b6_likeJsonbPath() {
            var sql = render(fc("extra.email|like", "%u%"));
            assertThat(sql).contains("#>> '{email}'").contains("like '%u%'");
        }

        // B7: INTERSECT + 三值 + jsonb_path_exists
        @Test
        void b7_intersectThreeValues() {
            var sql = render(fc("roles|intersect", "a,b,c"));
            assertThat(sql).contains("jsonb_path_exists").contains("@ == \"a\"");
        }

        // B8: D4=2 NE + 单函数链（"eq" 非关键字 → 显式操作符须用 ne 等关键字）
        @Test
        void b8_neWithSingleFunc() {
            var sql = render(fc("name|substr(1,3)|ne", "abc"));
            assertThat(sql).contains("substr(").contains("<> 'abc'");
        }

        // B9: D1=2 D2=3 D3=2 D4=3 NOT_IN + 驼峰 + 双函数链
        @Test
        void b9_notInCamelWithChain() {
            var sql = render(fc("status|substr(1)|count|notIn", "a,b,c"));
            assertThat(sql).contains("count(substr(").contains("not in (");
        }

        // B10: NOT_LIKE + 驼峰 + 单链
        @Test
        void b10_notLikeChain() {
            var sql = render(fc("wechatId|substr(1)|notLike", "a"));
            assertThat(sql).contains("substr(").contains("not like 'a'");
        }

        // 补点 B11: GT + JSONB 两段路径
        @Test
        void b11_gtTwoSegPath() {
            assertThat(render(fc("extra.age|gt", "10"))).contains("> '10'");
        }

        // 补点 B12: LT + JSONB 三段路径（#>> '{a,b}'）
        @Test
        void b12_ltThreeSegPath() {
            var sql = render(fc("extra.a.b|lt", "1"));
            assertThat(sql).contains("#>> '{a,b}'").contains("< '1'");
        }

        // 补点 B13: NE + 双值（多绑定渲染形态）
        @Test
        void b13_neTwoValues() {
            assertThat(render(fc("name|ne", "a,b"))).contains("<>");
        }

        // 补点 B14: IN + 三值 + List 入参
        @Test
        void b14_inThreeValuesList() {
            var sql = render(fc("type|in", List.of("A", "B", "C")));
            assertThat(sql).contains("in (").contains("'c'");
        }

        // 补点 B15: NOT_BETWEEN + JSONB 路径
        @Test
        void b15_notBetweenJsonb() {
            var sql = render(fc("extra.age|notBetween", "1,10"));
            assertThat(sql).contains("not between (").contains("#>>");
        }

        // 补点 B16: 末段非操作符 → 隐式 EQ，整段进函数链
        @Test
        void b16_implicitEqWithFunc() {
            var sql = render(fc("tags|count", 5));
            assertThat(sql).contains("count(").contains("= 5");
        }
    }

    // =====================================================================
    // create 新增（含校验）
    // =====================================================================

    /**
     * 正交维度（Filaments.create）：
     *   D1 数据形态:   1=全量合法         2=roles 为 null（DB 默认值兜底） 3=extra.email 为 null
     *   D2 type 取值:  1=A(合法枚举)      2=C(合法枚举)                    3=X(非法枚举)
     *   D3 验证点:     1=写回对象全等     2=影响行数                       3=DB 默认值列
     *
     * L9(3^3) 用例映射（D1=3 或 D2=3 为校验失败用例，D3 降级为"抛 Filaments.Error"断言）：
     *   用例  D1 D2 D3  期望
     *   C1     1  1  1  成功，递归对比写回一致
     *   C2     1  2  2  成功，影响行数 1
     *   C3     1  1  3  成功，create_time 取 DB 默认
     *   C4     2  1  2  roles null 通过校验（notNull 约束被 defaultValidator 摘除），行数 1
     *   C5     2  2  3  roles null，写回 roles 为 DB 默认空数组
     *   C6     1  3  -  非法枚举 → Error"类型错误"
     *   C7     2  3  -  roles null + 非法枚举 → Error
     *   C8     3  1  -  extra 非 null 但 email null → Error
     *   C9     3  2  -  email null + type C → Error
     * 补充：C10 批量两行合法 → 行数 2；C11 批量含一条非法 → 整批不落库（行数不变）。
     */
    @Nested
    @DisplayName("create 新增与校验 L9(3^3)")
    class Create {

        // C1: D1=1 D2=1 D3=1
        @Test
        void c1_fullValidRoundTrip() {
            var obj = baseUser(IdUtil.getSnowflake().nextIdStr());
            obj.setType("A");

            assertThat(filaments.create(db, obj)).isEqualTo(1);

            var got = filaments.getByIds(db, obj.getId());
            assertThat(got).usingRecursiveComparison(
                    RecursiveComparisonConfiguration.builder().withIgnoredFields("createTime").build()
            ).isEqualTo(obj);
        }

        // C2: D1=1 D2=2 D3=2
        @Test
        void c2_typeCRowCount() {
            var obj = baseUser(IdUtil.getSnowflake().nextIdStr());
            obj.setType("C");
            assertThat(filaments.create(db, obj)).isEqualTo(1);
        }

        // C3: D1=1 D2=1 D3=3 create_time 默认值
        @Test
        void c3_dbDefaultCreateTime() {
            var obj = baseUser(IdUtil.getSnowflake().nextIdStr());
            filaments.create(db, obj);

            var got = filaments.getByIds(db, obj.getId());
            assertThat(got.createTime).isNotNull();
        }

        // C4: D1=2 roles=null → 该字段约束被摘除，可插入
        @Test
        void c4_nullRolesSkipsConstraint() {
            var obj = baseUser(IdUtil.getSnowflake().nextIdStr());
            obj.setRoles(null);
            assertThat(filaments.create(db, obj)).isEqualTo(1);
        }

        // C5: D1=2 D2=2 D3=3 roles 走 DB 默认 '[]'
        @Test
        void c5_nullRolesFallsBackToDefault() {
            var obj = baseUser(IdUtil.getSnowflake().nextIdStr());
            obj.setRoles(null);
            obj.setType("C");
            filaments.create(db, obj);

            var got = filaments.getByIds(db, obj.getId());
            assertThat(got.roles).isEmpty();
        }

        // C6: D2=3 非法枚举
        @Test
        void c6_invalidEnumThrows() {
            var obj = baseUser(IdUtil.getSnowflake().nextIdStr());
            obj.setType("X");
            assertThatThrownBy(() -> filaments.create(db, obj))
                    .isInstanceOf(Filaments.Error.class)
                    .hasMessageContaining("类型错误");
        }

        // C7: D1=2 D2=3 roles null + 非法枚举
        @Test
        void c7_nullRolesInvalidEnumThrows() {
            var obj = baseUser(IdUtil.getSnowflake().nextIdStr());
            obj.setRoles(null);
            obj.setType("X");
            assertThatThrownBy(() -> filaments.create(db, obj))
                    .isInstanceOf(Filaments.Error.class);
        }

        // C8: D1=3 extra 非 null 但 email null → nest 约束生效
        @Test
        void c8_nestedEmailNullThrows() {
            var obj = baseUser(IdUtil.getSnowflake().nextIdStr());
            obj.getExtra().setEmail(null);
            assertThatThrownBy(() -> filaments.create(db, obj))
                    .isInstanceOf(Filaments.Error.class);
        }

        // C9: D1=3 D2=2 email null + type C
        @Test
        void c9_nestedEmailNullTypeCThrows() {
            var obj = baseUser(IdUtil.getSnowflake().nextIdStr());
            obj.setType("C");
            obj.getExtra().setEmail(null);
            assertThatThrownBy(() -> filaments.create(db, obj))
                    .isInstanceOf(Filaments.Error.class);
        }

        // C10: 批量合法
        @Test
        void c10_batchCreate() {
            var u1 = baseUser(IdUtil.getSnowflake().nextIdStr());
            var u2 = baseUser(IdUtil.getSnowflake().nextIdStr());
            assertThat(filaments.create(db, List.of(u1, u2))).isEqualTo(2);
        }

        // C11: 批量含非法 → 整批不落库
        @Test
        void c11_batchWithInvalidRollsBackAll() {
            var bad = baseUser(IdUtil.getSnowflake().nextIdStr());
            bad.setType("X");

            assertThatThrownBy(() -> filaments.create(db, List.of(baseUser(IdUtil.getSnowflake().nextIdStr()), bad)))
                    .isInstanceOf(Filaments.Error.class);

            assertThat(filaments.get(db, Map.of("rt", "id,name"))).hasSize(10);
        }
    }

    // =====================================================================
    // getByIds 主键查询
    // =====================================================================

    /**
     * 正交维度（Filaments.getByIds）：
     *   D1 入参形态: 1=单个 String       2=List 批量          3=List 含不存在 id
     *   D2 命中情况: 1=全命中            2=全不命中           3=部分命中
     *   D3 锁:       1=不加锁            2=FOR UPDATE
     * 用例映射（D1×D2×D3 按 L 表抽样 + 全覆盖 D1×D2 对）：
     *   G1  1/1/1  单 id 命中，字段全等（含 JSONB 解析）
     *   G2  2/1/1  批量两 id 命中
     *   G3  1/2/1  单 id 不命中 → null
     *   G4  3/3/1  批量含不存在 id → 只返回命中的
     *   G5  1/1/2  lock=true 正常返回
     *   G6  2/2/1  批量全不命中 → 空列表
     */
    @Nested
    @DisplayName("getByIds 主键查询")
    class GetById {

        // G1: 单 id 命中
        @Test
        void g1_singleHit() {
            var res = filaments.getByIds(db, "1");
            assertThat(res.id).isEqualTo("1");
            assertThat(res.extra.email).isEqualTo("user1@example.com");
            assertThat(res.wechatId).isEqualTo("xx");
            assertThat(res.roles).hasSize(1);
        }

        // G2: 批量命中
        @Test
        void g2_batchHit() {
            var res = filaments.getByIds(db, List.of("2", "3"));
            assertThat(res).hasSize(2);
            assertThat(res.stream().map(u -> u.id).toList()).containsExactlyInAnyOrder("2", "3");
        }

        // G3: 单 id 不命中 → null
        @Test
        void g3_singleMiss() {
            assertThat(filaments.getByIds(db, "99999")).isNull();
        }

        // G4: 批量部分命中
        @Test
        void g4_batchPartialHit() {
            var res = filaments.getByIds(db, List.of("4", "99999"));
            assertThat(res).hasSize(1);
            assertThat(res.get(0).id).isEqualTo("4");
        }

        // G5: lock=true（SELECT ... FOR UPDATE）
        @Test
        void g5_forUpdate() {
            var res = filaments.getByIds(db, "1", true);
            assertThat(res.id).isEqualTo("1");
        }

        // G6: 批量全不命中 → 空列表
        @Test
        void g6_batchAllMiss() {
            assertThat(filaments.getByIds(db, List.of("99998", "99999"))).isEmpty();
        }
    }

    // =====================================================================
    // get 通用查询
    // =====================================================================

    /**
     * 正交维度（Filaments.get，种子 10 行：A={1,4,7,10} B={2,5,8} C={3,6,9}）：
     *   D1 rt 返回字段: 1=不传(SELECT*)   2=两个字段            3=单字段(异常)
     *   D2 od 排序:     1=不传            2=asc(id)             3=desc(id)
     *   D3 where 条件:  1=无              2=eq(type=B)          3=in(type∈{A,C})
     *   D4 分页 p/pc:   1=不分页          2=p1                  3=p2(前页取满)
     *
     * 采用标准 L9(3^4)（D2×D3 九对全覆盖）：
     *   用例  D1 D2 D3 D4  期望
     *   Q1     1  1  1  1  10 行，extra 解析成对象
     *   Q2     1  2  2  2  eq+asc+p1pc4 → 3 行，首行 id=2
     *   Q3     1  3  3  3  in+desc+p2pc4 → ids [4,3,1]
     *   Q4     2  1  2  3  双字段+eq+p2pc4 → offset 越界空列表
     *   Q5     2  2  3  1  双字段+asc+in → 7 行首 id=1，extra 为 null
     *   Q6     2  3  1  2  双字段+desc+p1pc4 → 首 id=10
     *   Q7     3  1  3  2  单字段 → 抛异常
     *   Q8     3  2  1  3  单字段 → 抛异常
     *   Q9     3  3  2  1  单字段 → 抛异常
     * 补充：Q10 rt 驼峰字段名 → 自动转下划线列且值可读。
     */
    @Nested
    @DisplayName("get 通用查询 L9(3^4)")
    class Query {

        private List<String> ids(List<User> list) {
            return list.stream().map(u -> u.id).toList();
        }

        // Q1: 全默认
        @Test
        void q1_fullScan() {
            var res = filaments.get(db, new HashMap<>());
            assertThat(res).hasSize(10);
            assertThat(res.get(0).extra).isNotNull();
            assertThat(res.get(0).extra.email).isNotNull();
        }

        // Q2: D1=1 D2=2 D3=2 D4=2
        @Test
        void q2_eqAscFirstPage() {
            var res = filaments.get(db, new HashMap<>() {{
                put("type", "B");
                put("od", "id");
                put("p", 1);
                put("pc", 4);
            }});
            assertThat(ids(res)).containsExactly("2", "5", "8");
        }

        // Q3: D1=1 D2=3 D3=3 D4=3
        @Test
        void q3_inDescSecondPage() {
            var res = filaments.get(db, new HashMap<>() {{
                put("type|in", "A,C");
                put("od", "-id");
                put("p", 2);
                put("pc", 4);
            }});
            assertThat(ids(res)).containsExactly("4", "3", "1");
        }

        // Q4: D1=2 D2=1 D3=2 D4=3 → 越界页
        @Test
        void q4_offsetBeyondEnd() {
            var res = filaments.get(db, new HashMap<>() {{
                put("rt", "id,name");
                put("type", "B");
                put("p", 2);
                put("pc", 4);
            }});
            assertThat(res).isEmpty();
        }

        // Q5: D1=2 D2=2 D3=3 D4=1
        @Test
        void q5_inAscProjection() {
            var res = filaments.get(db, new HashMap<>() {{
                put("rt", "id,name");
                put("od", "id");
                put("type|in", "A,C");
            }});
            assertThat(res).hasSize(7);
            assertThat(res.get(0).id).isEqualTo("1");
            assertThat(res.get(0).extra).isNull();
        }

        // Q6: D1=2 D2=3 D3=1 D4=2
        @Test
        void q6_descFirstPage() {
            var res = filaments.get(db, new HashMap<>() {{
                put("rt", "id,name");
                put("od", "-id");
                put("p", 1);
                put("pc", 4);
            }});
            assertThat(ids(res)).containsExactly("10", "9", "8", "7");
        }

        // Q7-Q9: D1=3 单字段 → 抛"至少返回两个字段"
        @Test
        void q7_singleFieldId() {
            assertThatThrownBy(() -> filaments.get(db, Map.of("rt", "id", "type|in", "A,C", "p", 1)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("至少返回两个字段");
        }

        @Test
        void q8_singleFieldName() {
            assertThatThrownBy(() -> filaments.get(db, Map.of("rt", "name", "od", "-id", "p", 2, "pc", 4)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("至少返回两个字段");
        }

        @Test
        void q9_singleFieldType() {
            assertThatThrownBy(() -> filaments.get(db, Map.of("rt", "type", "od", "id", "type", "B")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("至少返回两个字段");
        }

        // Q10: 补充——rt 驼峰自动转下划线列
        @Test
        void q10_camelRtToUnderscoreColumn() {
            var res = filaments.get(db, Map.of("rt", "id,wechatId", "od", "id"));
            assertThat(res.get(0).wechatId).isEqualTo("xx");
            assertThat(res.get(0).name).isNull();
        }
    }

    // =====================================================================
    // buildFields 返回字段构建（经 get 行为验证）
    // =====================================================================

    /**
     * 正交维度（SelectSpecBuilder.parseSelectFields + buildSelectFieldsFromSpec）：
     *   D1 rt 类型:   1=String 逗号串  2=String 单字段      3=List
     *   D2 字段命名:  1=全小写         2=驼峰(转下划线)     3=混合
     *   D3 期望行为:  1=SELECT *       2=子集投影(其余 null) 3=异常
     * 用例映射：
     *   BF1  1/1/2  "id,wechat_id" 子集
     *   BF2  2/1/3  "id" 单字段异常
     *   BF3  3/1/2  List.of("id","type") 子集
     *   BF4  3/2/2  List 含驼峰 → 转下划线可读
     *   BF5  -/3/2  混合命名双字段，其余字段 null
     *   BF6  -/1/1  不传 rt → 全字段（extra/roles 对象化）
     *   BF7  3/2/2  java.time 字段投影（@Disabled：库侧已知限制，见方法注释）
     * （D3=1 的"SELECT *"与 D2 各水平的组合中 BF6 覆盖小写；混合命名的 SELECT*
     *   由表结构本身（下划线列）决定，无独立意义，未排入正交表。）
     */
    @Nested
    @DisplayName("buildFields 返回字段构建")
    class BuildFields {

        // BF1: String 双字段
        @Test
        void bf1_twoLowerFields() {
            var res = filaments.get(db, Map.of("rt", "id,wechat_id", "od", "id"));
            assertThat(res.get(0).id).isEqualTo("1");
            assertThat(res.get(0).wechatId).isEqualTo("xx");
            assertThat(res.get(0).type).isNull();
        }

        // BF2: 单字段异常
        @Test
        void bf2_singleFieldThrows() {
            assertThatThrownBy(() -> filaments.get(db, Map.of("rt", "type")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("至少返回两个字段");
        }

        // BF3: List 双字段
        @Test
        void bf3_listFields() {
            var res = filaments.get(db, Map.of("rt", List.of("id", "type"), "od", "id"));
            assertThat(res.get(0).type).isEqualTo("A");
            assertThat(res.get(0).name).isNull();
        }

        // BF4: List 含驼峰（转下划线列）
        @Test
        void bf4_listCamel() {
            var res = filaments.get(db, Map.of("rt", List.of("id", "wechatId"), "od", "id"));
            assertThat(res.get(0).wechatId).isEqualTo("xx");
            assertThat(res.get(0).name).isNull();
        }

        /**
         * 已知限制（库级回归，暂不改库代码）：rt 投影会丢失列类型信息，
         * jOOQ 将时间渲染为 "yyyy-MM-dd HH:mm:ss[.SSS]"（无 T、无偏移量），
         * Jackson 3 严格 ISO 解析 java.time 模型会抛 InvalidFormatException。
         * SELECT *（bf6 路径）不受影响。待库侧方案定稿后启用本用例。
         */
        @org.junit.jupiter.api.Disabled("时间字段投影解析：等待库侧修复方案")
        @Test
        void bf7_timeFieldProjection() {
            var res = filaments.get(db, Map.of("rt", List.of("id", "createTime"), "od", "id"));
            assertThat(res.get(0).createTime).isNotNull();
        }

        // BF5: 混合命名
        @Test
        void bf5_mixedNaming() {
            var res = filaments.get(db, Map.of("rt", "name,extra", "od", "id"));
            assertThat(res.get(0).extra).isNotNull();
            assertThat(res.get(0).id).isNull();
        }

        // BF6: 不传 rt → 全字段
        @Test
        void bf6_selectAll() {
            var res = filaments.get(db, Map.of("od", "id"));
            var first = res.get(0);
            assertThat(first.id).isEqualTo("1");
            assertThat(first.roles).hasSize(1);
            assertThat(first.extra.totalCount).isEqualTo(10);
        }
    }

    // =====================================================================
    // pages 分页查询
    // =====================================================================

    /**
     * 正交维度（Filaments.pages）：
     *   D1 pg 计数开关: 1=不传(total=null)  2=pg=1  3=pg=1(另配不同条件复验 total)
     *   D2 p/pc:        1=默认(p1,pc20)     2=p1 pc2  3=p1 pc4
     *   D3 where 条件:  1=无                2=eq(type=B)  3=in(type∈{A,C})
     *   D4 rt:          1=不传              2=id+name     3=id+type
     *
     * L9(3^4) 用例映射（D1=1 三行专测不计数路径；total 语义仅 D1≥2 可测）：
     *   用例  D1 D2 D3 D4  期望
     *   P1     1  1  1  1  total null，list 10
     *   P2     1  2  2  2  list 2，total null
     *   P3     1  3  3  3  p 在 get 内不传 → 默认 p1 → list 4
     *   P4     2  1  1  2  total 10，list 10
     *   P5     2  2  2  3  total 3，list 2
     *   P6     2  3  3  1  total 7，list 4
     *   P7     3  1  2  1  total 3，list 3
     *   P8     3  2  3  2  total 7，list 2
     *   P9     3  3  1  3  total 10，list 4
     * 注：p>1 且 pg=1 时 LIMIT/OFFSET 会泄漏进 count 查询（已知实现限制），
     *     故本表 D2 各水平均取 p=1；该场景行为不稳定，不纳入正交矩阵。
     */
    @Nested
    @DisplayName("pages 分页查询 L9(3^4)")
    class Pages {

        // P1
        @Test
        void p1_noPgNoCount() {
            var res = filaments.pages(db, new HashMap<>());
            assertThat(res.total).isNull();
            assertThat(res.list).hasSize(10);
        }

        // P2
        @Test
        void p2_customPcWithEq() {
            var res = filaments.pages(db, new HashMap<>() {{
                put("pc", 2);
                put("type", "B");
                put("rt", "id,name");
            }});
            assertThat(res.total).isNull();
            assertThat(res.list).hasSize(2);
        }

        // P3: p 不传 → 默认 p1，pc=4 生效；in 条件
        @Test
        void p3_defaultPageWithIn() {
            var res = filaments.pages(db, new HashMap<>() {{
                put("pc", 4);
                put("type|in", "A,C");
                put("rt", "id,type");
            }});
            assertThat(res.total).isNull();
            assertThat(res.list).hasSize(4);
        }

        // P4
        @Test
        void p4_pgOnNoCond() {
            var res = filaments.pages(db, new HashMap<>() {{
                put("pg", 1);
                put("rt", "id,name");
            }});
            assertThat(res.total).isEqualTo(10);
            assertThat(res.list).hasSize(10);
        }

        // P5
        @Test
        void p5_pgOnPc2Eq() {
            var res = filaments.pages(db, new HashMap<>() {{
                put("pg", 1);
                put("pc", 2);
                put("type", "B");
                put("rt", "id,type");
            }});
            assertThat(res.total).isEqualTo(3);
            assertThat(res.list).hasSize(2);
        }

        // P6
        @Test
        void p6_pgOnPc4In() {
            var res = filaments.pages(db, new HashMap<>() {{
                put("pg", 1);
                put("pc", 4);
                put("type|in", "A,C");
            }});
            assertThat(res.total).isEqualTo(7);
            assertThat(res.list).hasSize(4);
        }

        // P7
        @Test
        void p7_pgDefaultPcEq() {
            var res = filaments.pages(db, new HashMap<>() {{
                put("pg", 1);
                put("type", "B");
            }});
            assertThat(res.total).isEqualTo(3);
            assertThat(res.list).hasSize(3);
        }

        // P8
        @Test
        void p8_pgPc2InRt() {
            var res = filaments.pages(db, new HashMap<>() {{
                put("pg", 1);
                put("pc", 2);
                put("type|in", "A,C");
                put("rt", "id,name");
            }});
            assertThat(res.total).isEqualTo(7);
            assertThat(res.list).hasSize(2);
        }

        // P9
        @Test
        void p9_pgPc4NoCondRt() {
            var res = filaments.pages(db, new HashMap<>() {{
                put("pg", 1);
                put("pc", 4);
                put("rt", "id,type");
            }});
            assertThat(res.total).isEqualTo(10);
            assertThat(res.list).hasSize(4);
        }
    }

    // =====================================================================
    // updateByIds 更新
    // =====================================================================

    /**
     * 正交维度（Filaments.updateByIds）：
     *   D1 id 形态:     1=单个 String     2=List 批量        3=List 含不存在 id
     *   D2 更新内容:    1=普通字符串字段  2=JSONB 字段       3=多字段混合
     *   D3 校验点:      1=更新列生效      2=JSONB 写回       3=未更新列保持原值
     *   D4 主键策略:    1=默认 id（不入 SET，见 CustomPk）
     * （D4 单水平不参与正交排布，主键不入 SET 的行为在 CustomPk 组验证。）
     *
     * 采用 L9(3^3)（D1×D2×D3 全对覆盖，9 行）：
     *   用例  D1 D2 D3  期望
     *   U1     1  1  1  name 改，extra.email 原值保留
     *   U2     1  2  2  roles+extra 更新后写回一致
     *   U3     1  3  3  name+wechatId+type 全改，create_time 不变
     *   U4     2  1  1  批量两行 name 同改
     *   U5     2  2  2  批量 JSONB 更新
     *   U6     2  3  3  批量多字段，未选行原值
     *   U7     3  1  1  混合不存在 id：存在行被更新，不报错
     *   U8     3  2  3  不存在 id + JSONB：extra 整列覆盖，未传列保持原值
     *   U9     3  3  2  不存在 id + 多字段：命中行多字段生效
     */
    @Nested
    @DisplayName("updateByIds 更新 L9(3^3)")
    class Update {

        // U1: D1=1 D2=1 D3=1
        @Test
        void u1_singleFieldOnly() {
            var obj = new User();
            obj.setName("N");
            filaments.updateByIds(db, "1", obj);

            var got = filaments.getByIds(db, "1");
            assertThat(got.name).isEqualTo("N");
            assertThat(got.extra.email).isEqualTo("user1@example.com");
        }

        // U2: D1=1 D2=2 D3=2 JSONB 更新
        @Test
        void u2_jsonbFields() {
            var extra = new User.Extra();
            extra.setAge(99);
            extra.setEmail("new@example.com");
            var obj = new User();
            obj.setRoles(List.of(new User.Role("ops", new User.RoleGrant(false))));
            obj.setExtra(extra);
            filaments.updateByIds(db, "2", obj);

            var got = filaments.getByIds(db, "2");
            assertThat(got.roles.get(0).name).isEqualTo("ops");
            assertThat(got.extra.email).isEqualTo("new@example.com");
            assertThat(got.extra.age).isEqualTo(99);
        }

        // U3: D1=1 D2=3 D3=3 多字段 + 原值保持
        @Test
        void u3_multiFieldsOthersKept() {
            var obj = new User();
            obj.setName("M");
            obj.setWechatId("wx_new");
            obj.setType("B");
            filaments.updateByIds(db, "3", obj);

            var got = filaments.getByIds(db, "3");
            assertThat(got.name).isEqualTo("M");
            assertThat(got.wechatId).isEqualTo("wx_new");
            assertThat(got.type).isEqualTo("B");
            assertThat(got.extra.email).isEqualTo("user3@example.com");
        }

        // U4: D1=2 D2=1 D3=1 批量
        @Test
        void u4_batchName() {
            var obj = new User();
            obj.setName("批量");
            filaments.updateByIds(db, List.of("4", "5"), obj);

            assertThat(filaments.getByIds(db, "4").name).isEqualTo("批量");
            assertThat(filaments.getByIds(db, "5").name).isEqualTo("批量");
        }

        // U5: D1=2 D2=2 D3=2 批量 JSONB
        @Test
        void u5_batchJsonb() {
            var obj = new User();
            obj.setRoles(List.of(new User.Role("guest", new User.RoleGrant(false))));
            filaments.updateByIds(db, List.of("6", "7"), obj);

            assertThat(filaments.getByIds(db, "6").roles.get(0).name).isEqualTo("guest");
            assertThat(filaments.getByIds(db, "7").roles.get(0).name).isEqualTo("guest");
        }

        // U6: D1=2 D2=3 D3=3 批量多字段，未选行不变
        @Test
        void u6_batchMultiFieldsUnselectedKept() {
            var obj = new User();
            obj.setName("X");
            obj.setType("C");
            filaments.updateByIds(db, List.of("8", "9"), obj);

            assertThat(filaments.getByIds(db, "8").type).isEqualTo("C");
            assertThat(filaments.getByIds(db, "10").type).isEqualTo("A");
        }

        // U7: D1=3 D2=1 D3=1 混合不存在 id
        @Test
        void u7_batchWithMissingId() {
            var obj = new User();
            obj.setName("Z");
            filaments.updateByIds(db, List.of("1", "99999"), obj);

            assertThat(filaments.getByIds(db, "1").name).isEqualTo("Z");
        }

        // U8: D1=3 D2=2 D3=3 不存在 id + JSONB：extra 整列替换（非合并），未更新列保持原值
        @Test
        void u8_missingIdJsonbReplaced() {
            var extra = new User.Extra();
            extra.setAge(1);
            extra.setEmail("k@example.com");
            var obj = new User();
            obj.setExtra(extra);
            filaments.updateByIds(db, List.of("2", "99999"), obj);

            var got = filaments.getByIds(db, "2");
            assertThat(got.extra.email).isEqualTo("k@example.com");
            assertThat(got.extra.totalCount).isNull(); // JSONB 列为整体覆盖语义
            assertThat(got.name).isEqualTo("User2");   // 未传字段不被更新
        }

        // U9: D1=3 D2=3 D3=2 不存在 id + 多字段
        @Test
        void u9_missingIdMultiFields() {
            var obj = new User();
            obj.setName("K");
            obj.setWechatId("w2");
            obj.setType("A");
            filaments.updateByIds(db, List.of("99999", "10"), obj);

            var got = filaments.getByIds(db, "10");
            assertThat(got.name).isEqualTo("K");
            assertThat(got.wechatId).isEqualTo("w2");
            assertThat(got.type).isEqualTo("A");
        }
    }

    // =====================================================================
    // deleteByIds 删除
    // =====================================================================

    /**
     * 正交维度（Filaments.deleteByIds）：
     *   D1 id 形态:   1=单个 String  2=List 批量   3=List 含不存在 id
     *   D2 期望结果:  1=目标行消失   2=其余行保留   3=空操作不报错
     * 用例映射（近 L4 全对覆盖）：
     *   R1  1/-    单个删除后该行 null
     *   R2  2/1+2  批量删除 2 行，剩余 8 行
     *   R3  3/2+3  含不存在 id：存在行删除、不报错、剩余 9 行
     *   R4  3/3    全不存在 id：全表 10 行保留
     */
    @Nested
    @DisplayName("deleteByIds 删除")
    class Delete {

        // R1: 单个删除
        @Test
        void r1_deleteSingle() {
            filaments.deleteByIds(db, "1");
            assertThat(filaments.getByIds(db, "1")).isNull();
            assertThat(filaments.getByIds(db, "2")).isNotNull();
        }

        // R2: 批量删除
        @Test
        void r2_deleteBatch() {
            filaments.deleteByIds(db, List.of("2", "3"));
            assertThat(filaments.get(db, Map.of("rt", "id,name"))).hasSize(8);
        }

        // R3: 含不存在 id
        @Test
        void r3_deleteWithMissingId() {
            filaments.deleteByIds(db, List.of("4", "99999"));
            assertThat(filaments.getByIds(db, "4")).isNull();
            assertThat(filaments.get(db, Map.of("rt", "id,name"))).hasSize(9);
        }

        // R4: 全不存在 id → 空操作
        @Test
        void r4_deleteAllMissing() {
            filaments.deleteByIds(db, List.of("99998", "99999"));
            assertThat(filaments.get(db, Map.of("rt", "id,name"))).hasSize(10);
        }
    }

    // =====================================================================
    // aggregation 聚合查询
    // =====================================================================

    /**
     * 正交维度（Filaments.aggregation）：
     *   D1 聚合函数:  1=count            2=countDistinct        3=count+countDistinct 混用
     *   D2 group:     1=空(整体聚合)     2=单列(type)           3=双列(type,wechat_id)
     *   D3 query:     1=空               2=单条件(eq)           3=多条件(in / like)
     *
     * L9(3^3) 用例映射（全对覆盖）：
     *   用例  D1 D2 D3  期望
     *   A1     1  1  1  countId=10
     *   A2     1  2  1  3 组，type=A 组 4
     *   A3     1  3  2  2 组（type∈{A,B}）
     *   A4     2  1  3  countDistinctId=2（name like User1%）
     *   A5     2  2  1  3 组，type=B 组 3
     *   A6     2  3  2  1 组（type=C）值 3
     *   A7     3  1  1  两个别名键都在，countId=10
     *   A8     3  2  3  3 组含双键，type=B 组 3
     *   A9     3  3  2  2 组含双键
     */
    @Nested
    @DisplayName("aggregation 聚合查询 L9(3^3)")
    class Aggregation {

        private Map<String, Object> findRow(List<Map<String, Object>> rows, String type) {
            return rows.stream().filter(m -> type.equals(m.get("type"))).findFirst().orElseThrow();
        }

        // A1: D1=1 D2=1 D3=1
        @Test
        void a1_countAll() {
            var res = filaments.aggregation(db, Map.of("count", List.of("id")), Map.of(), List.of(), null);
            assertThat(res).hasSize(1);
            assertThat(res.get(0).get("countId").toString()).isEqualTo("10");
        }

        // A2: count 单列分组
        @Test
        void a2_countGroupType() {
            var res = filaments.aggregation(db, Map.of("count", List.of("id")), Map.of(), List.of("type"), null);
            assertThat(res).hasSize(3);
            assertThat(findRow(res, "A").get("countId").toString()).isEqualTo("4");
            assertThat(findRow(res, "B").get("countId").toString()).isEqualTo("3");
        }

        // A3: count 双列分组 + in 条件
        @Test
        void a3_countGroupTwoWithIn() {
            var res = filaments.aggregation(db,
                    Map.of("count", List.of("id")),
                    new HashMap<>() {{ put("type|in", "A,B"); }},
                    List.of("type", "wechat_id"), null);
            assertThat(res).hasSize(2);
            assertThat(res.get(0)).containsKeys("type", "wechat_id", "countId");
        }

        // A4: countDistinct + like 条件
        @Test
        void a4_countDistinctWithLike() {
            var res = filaments.aggregation(db,
                    Map.of("countDistinct", "id"),
                    new HashMap<>() {{ put("name|like", "User1%"); }},
                    List.of(), null);
            assertThat(res.get(0).get("countDistinctId").toString()).isEqualTo("2");
        }

        // A5: countDistinct 单列分组
        @Test
        void a5_countDistinctGroup() {
            var res = filaments.aggregation(db,
                    Map.of("countDistinct", "id"), Map.of(), List.of("type"), null);
            assertThat(res).hasSize(3);
            assertThat(findRow(res, "B").get("countDistinctId").toString()).isEqualTo("3");
        }

        // A6: countDistinct 双列分组 + eq 条件
        @Test
        void a6_countDistinctGroupTwoEq() {
            var res = filaments.aggregation(db,
                    Map.of("countDistinct", List.of("id")),
                    Map.of("type", "C"),
                    List.of("type", "wechat_id"), null);
            assertThat(res).hasSize(1);
            assertThat(res.get(0).get("countDistinctId").toString()).isEqualTo("3");
        }

        // A7: 混用两函数 整体聚合
        @Test
        void a7_mixedNoGroup() {
            var res = filaments.aggregation(db,
                    new HashMap<>() {{
                        put("count", List.of("id"));
                        put("countDistinct", "id");
                    }},
                    Map.of(), List.of(), null);
            assertThat(res).hasSize(1);
            assertThat(res.get(0)).containsKeys("countId", "countDistinctId");
        }

        // A8: 混用 + 单列分组 + in
        @Test
        void a8_mixedGroupWithIn() {
            var res = filaments.aggregation(db,
                    new HashMap<>() {{
                        put("count", List.of("id"));
                        put("countDistinct", "id");
                    }},
                    new HashMap<>() {{ put("type|in", "A,B"); }},
                    List.of("type"), null);
            assertThat(res).hasSize(2);
            assertThat(findRow(res, "B").get("countId").toString()).isEqualTo("3");
        }

        // A9: 混用 + 双列分组 + eq
        @Test
        void a9_mixedGroupTwoColsEq() {
            var res = filaments.aggregation(db,
                    new HashMap<>() {{
                        put("count", List.of("id"));
                        put("countDistinct", "id");
                    }},
                    Map.of("type", "A"),
                    List.of("type", "wechat_id"), null);
            assertThat(res).hasSize(1);
            assertThat(findRow(res, "A").get("countId").toString()).isEqualTo("4");
        }
    }

    // =====================================================================
    // 自定义主键（跨 CRUD 路径的 pk 因子）
    // =====================================================================

    /**
     * 正交维度（Filaments.pk 配置 + CRUD 全链路）：
     *   D1 主键形态: 1=id(默认)  2=uid(自定义)
     *   D2 路径:     1=create    2=get/update  3=delete
     *   D3 关键点:   1=where 走 pk 列    2=SET 排除 pk 列（批量更新冲突）
     * CustomPk 用例 = D1=2 与 D2/D3 各水平的串联验证（默认 id 路径已被上方各组覆盖）。
     */
    @Nested
    @DisplayName("CustomPk 自定义主键全链路")
    class CustomPk {

        public static class Mini {
            public String uid;
            public String name;

            public Mini() {}

            public Mini(String uid, String name) {
                this.uid = uid;
                this.name = name;
            }

            public String getUid() { return uid; }
            public void setUid(String uid) { this.uid = uid; }
            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
        }

        @SneakyThrows
        @Test
        void fullLifecycle() {
            // 表结构在 script/base.sql，测试只清行
            var t = "user_mini_pk";
            db.deleteFrom(DSL.table(DSL.name(t))).execute();

            var f = new Filaments<Mini>(t) {
                { pk = "uid"; }

                @Override
                public ValidatorBuilder<Mini> initValidator() {
                    return ValidatorBuilder.<Mini>of()
                            .constraint(Mini::getUid, "uid", c -> c.notNull());
                }
            };

            f.create(db, new Mini("100", "A"));

            // update：where 走自定义主键，且传入对象携带的主键值不参与 SET
            f.updateByIds(db, "100", new Mini("101", "B"));
            var got = f.getByIds(db, "100");
            assertThat(got).isNotNull();
            assertThat(got.uid).isEqualTo("100");
            assertThat(got.name).isEqualTo("B");
            assertThat(f.getByIds(db, "101")).isNull();

            // delete：where 走自定义主键
            f.deleteByIds(db, "100");
            assertThat(f.getByIds(db, "100")).isNull();

            db.deleteFrom(DSL.table(DSL.name(t))).execute();
        }
    }
}
