package me.karboom.java.filaments;

import me.karboom.java.filaments.spec.FieldCondition;
import me.karboom.java.filaments.spec.LogicNode;
import me.karboom.java.filaments.spec.OrderSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static me.karboom.java.filaments.spec.LogicNode.AND;
import static me.karboom.java.filaments.spec.LogicNode.OR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SelectSpecBuilder.parse 正交法测试套件。
 *
 * 每个 @Nested 分组对应一个解析维度簇，类注释给出因子/水平与用例映射。
 * 跨因子组合的 SQL 行为验证见 FilamentsTest（Query/Create/Aggregation 组）。
 *
 * 覆盖 selectFields、where（含逻辑组 lg）、orderBy、limit/offset 及完整查询。
 */
public class SelectSpecBuilderTest {

    private final SelectSpecBuilder builder = new SelectSpecBuilder();

    /**
     * 正交维度（rt 解析）：
     *   D1 值类型:   1=不传(null)  2=String 逗号串  3=List
     *   D2 字段个数: 1=0 个        2=1 个           3=2~3 个
     *   D3 字段命名: 1=全小写      2=驼峰           3=混合
     * 用例映射（L 型近似全对）：
     *   S1  1/-/1   null → SELECT *（selectFields 为 null）
     *   S2  2/3/1   "id,name,email"
     *   S3  3/3/1   List 三字段
     *   S4  2/2/1   单字段 "id"
     *   S5  2/2/2   驼峰保持原样
     *   S6  3/2/3   List 混合命名
     *   S7  边界    非法类型（Integer）→ IllegalStateException
     */
    @Nested
    @DisplayName("selectFields 解析")
    class SelectFields {

        // S1: 不传 rt → null
        @Test
        void nullRt() {
            var spec = builder.parse(new HashMap<>(), null);
            assertThat(spec.getSelectFields()).isNull();
        }

        // S2: String 逗号串
        @Test
        void csvString() {
            var spec = builder.parse(new HashMap<>() {{
                put("rt", "id,name,email");
            }}, null);
            assertThat(spec.getSelectFields()).containsExactly("id", "name", "email");
        }

        // S3: List 类型
        @Test
        void listType() {
            var spec = builder.parse(new HashMap<>() {{
                put("rt", List.of("id", "name", "extra"));
            }}, null);
            assertThat(spec.getSelectFields()).containsExactly("id", "name", "extra");
        }

        // S4: 单字段
        @Test
        void singleField() {
            var spec = builder.parse(new HashMap<>() {{
                put("rt", "id");
            }}, null);
            assertThat(spec.getSelectFields()).containsExactly("id");
        }

        // S5: 驼峰保持原样（列名转换发生在下游 buildSelectFields）
        @Test
        void camelPreserved() {
            var spec = builder.parse(new HashMap<>() {{
                put("rt", "userId,createTime");
            }}, null);
            assertThat(spec.getSelectFields()).containsExactly("userId", "createTime");
        }

        // S6: List 混合命名
        @Test
        void listMixedNaming() {
            var spec = builder.parse(new HashMap<>() {{
                put("rt", List.of("id", "createTime", "wechat_id"));
            }}, null);
            assertThat(spec.getSelectFields()).containsExactly("id", "createTime", "wechat_id");
        }

        // S7: 非法类型
        @Test
        void invalidRtType() {
            assertThatThrownBy(() -> builder.parse(new HashMap<>() {{
                put("rt", 123);
            }}, null)).isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * 正交维度（where 条件解析）：
     *   D1 操作符:  eq/ne/gt/ge/lt/le/in/notIn/between/notBetween/like/notLike/intersect（13 种全枚举）
     *   D2 值形态:  String 逗号串 / List / 单标量
     *   D3 字段形态: 普通 / 驼峰 / JSONB dot 路径
     *   D4 函数链:  无 / 显式操作符前的中间段 / 末段隐式函数
     * 主组 testParseWhere 覆盖 D1 全水平 × D2={String 串, List}；
     * Supplement 组补齐 D1×D3（dot 路径、驼峰）、D1×D4（函数链）、值 List 化的范围条件。
     */
    @Nested
    @DisplayName("where 操作符解析")
    class WhereOperators {

        @Test
        void testParseWhere() {
            // 情况 1: 空查询，无条件
            var query1 = new HashMap<String, Object>();
            var spec1 = builder.parse(query1, null);
            assertThat(spec1.getWhere().getValue()).isEmpty();

            // 情况 2: 简单等值条件
            var query2 = new HashMap<String, Object>() {{
                put("name", "John");
            }};
            var spec2 = builder.parse(query2, null);
            assertThat(spec2.getWhere().getValue()).hasSize(1);
            var cond2 = (FieldCondition) spec2.getWhere().getValue().get(0);
            assertThat(cond2.getField()).isEqualTo("name");
            assertThat(cond2.getOperator()).isEqualTo(FieldCondition.EQ);
            assertThat(cond2.getValues()).containsExactly("John");

            // 情况 3: 比较操作符 ge
            var query3 = new HashMap<String, Object>() {{
                put("age|ge", "18");
            }};
            var spec3 = builder.parse(query3, null);
            var cond3 = (FieldCondition) spec3.getWhere().getValue().get(0);
            assertThat(cond3.getField()).isEqualTo("age");
            assertThat(cond3.getOperator()).isEqualTo(FieldCondition.GE);
            assertThat(cond3.getValues()).containsExactly("18");

            // 情况 4: 比较操作符 gt
            var query4 = new HashMap<String, Object>() {{
                put("age|gt", "18");
            }};
            var spec4 = builder.parse(query4, null);
            var cond4 = (FieldCondition) spec4.getWhere().getValue().get(0);
            assertThat(cond4.getOperator()).isEqualTo(FieldCondition.GT);

            // 情况 5: 比较操作符 le
            var query5 = new HashMap<String, Object>() {{
                put("age|le", "60");
            }};
            var spec5 = builder.parse(query5, null);
            var cond5 = (FieldCondition) spec5.getWhere().getValue().get(0);
            assertThat(cond5.getOperator()).isEqualTo(FieldCondition.LE);

            // 情况 6: 比较操作符 lt
            var query6 = new HashMap<String, Object>() {{
                put("age|lt", "60");
            }};
            var spec6 = builder.parse(query6, null);
            var cond6 = (FieldCondition) spec6.getWhere().getValue().get(0);
            assertThat(cond6.getOperator()).isEqualTo(FieldCondition.LT);

            // 情况 7: 比较操作符 ne
            var query7 = new HashMap<String, Object>() {{
                put("status|ne", "deleted");
            }};
            var spec7 = builder.parse(query7, null);
            var cond7 = (FieldCondition) spec7.getWhere().getValue().get(0);
            assertThat(cond7.getOperator()).isEqualTo(FieldCondition.NE);

            // 情况 8: IN 操作符，逗号分隔字符串
            var query8 = new HashMap<String, Object>() {{
                put("status|in", "active,pending");
            }};
            var spec8 = builder.parse(query8, null);
            var cond8 = (FieldCondition) spec8.getWhere().getValue().get(0);
            assertThat(cond8.getOperator()).isEqualTo(FieldCondition.IN);
            assertThat(cond8.getValues()).containsExactly("active", "pending");

            // 情况 9: IN 操作符，List 类型
            var query9 = new HashMap<String, Object>() {{
                put("status|in", List.of("active", "pending"));
            }};
            var spec9 = builder.parse(query9, null);
            var cond9 = (FieldCondition) spec9.getWhere().getValue().get(0);
            assertThat(cond9.getOperator()).isEqualTo(FieldCondition.IN);
            assertThat(cond9.getValues()).containsExactly("active", "pending");

            // 情况 10: NOT_IN 操作符
            var query10 = new HashMap<String, Object>() {{
                put("status|notIn", "deleted,archived");
            }};
            var spec10 = builder.parse(query10, null);
            var cond10 = (FieldCondition) spec10.getWhere().getValue().get(0);
            assertThat(cond10.getOperator()).isEqualTo(FieldCondition.NOT_IN);

            // 情况 11: BETWEEN 操作符
            var query11 = new HashMap<String, Object>() {{
                put("age|between", "18,60");
            }};
            var spec11 = builder.parse(query11, null);
            var cond11 = (FieldCondition) spec11.getWhere().getValue().get(0);
            assertThat(cond11.getOperator()).isEqualTo(FieldCondition.BETWEEN);
            assertThat(cond11.getValues()).containsExactly("18", "60");

            // 情况 12: NOT_BETWEEN 操作符
            var query12 = new HashMap<String, Object>() {{
                put("age|notBetween", "18,60");
            }};
            var spec12 = builder.parse(query12, null);
            var cond12 = (FieldCondition) spec12.getWhere().getValue().get(0);
            assertThat(cond12.getOperator()).isEqualTo(FieldCondition.NOT_BETWEEN);

            // 情况 13: LIKE 操作符
            var query13 = new HashMap<String, Object>() {{
                put("name|like", "%John%");
            }};
            var spec13 = builder.parse(query13, null);
            var cond13 = (FieldCondition) spec13.getWhere().getValue().get(0);
            assertThat(cond13.getOperator()).isEqualTo(FieldCondition.LIKE);

            // 情况 14: NOT_LIKE 操作符
            var query14 = new HashMap<String, Object>() {{
                put("name|notLike", "%test%");
            }};
            var spec14 = builder.parse(query14, null);
            var cond14 = (FieldCondition) spec14.getWhere().getValue().get(0);
            assertThat(cond14.getOperator()).isEqualTo(FieldCondition.NOT_LIKE);

            // 情况 15: INTERSECT 操作符
            var query15 = new HashMap<String, Object>() {{
                put("tags|intersect", "java,spring");
            }};
            var spec15 = builder.parse(query15, null);
            var cond15 = (FieldCondition) spec15.getWhere().getValue().get(0);
            assertThat(cond15.getOperator()).isEqualTo(FieldCondition.INTERSECT);

            // 情况 16: 多个条件
            var query16 = new HashMap<String, Object>() {{
                put("name", "John");
                put("age|ge", "18");
                put("status", "active");
            }};
            var spec16 = builder.parse(query16, null);
            assertThat(spec16.getWhere().getValue()).hasSize(3);

            // 情况 17: 驼峰命名保持原样
            var query17 = new HashMap<String, Object>() {{
                put("createTime|ge", "2024-01-01");
            }};
            var spec17 = builder.parse(query17, null);
            var cond17 = (FieldCondition) spec17.getWhere().getValue().get(0);
            assertThat(cond17.getField()).isEqualTo("createTime");
        }

        // 补点 W1: D3=dot 路径 × D1=LIKE
        @Test
        void jsonbDotPathLike() {
            var spec = builder.parse(new HashMap<>() {{
                put("extra.email|like", "%u%");
            }}, null);
            var cond = (FieldCondition) spec.getWhere().getValue().get(0);
            assertThat(cond.getField()).isEqualTo("extra.email");
            assertThat(cond.getOperator()).isEqualTo(FieldCondition.LIKE);
        }

        // 补点 W2: D4=显式操作符前的函数中间段（"eq" 非关键字，显式段须为 ne/in 等关键字）
        @Test
        void funcChainBeforeExplicitOperator() {
            var spec = builder.parse(new HashMap<>() {{
                put("name|substr(1,3)|ne", "abc");
            }}, null);
            var cond = (FieldCondition) spec.getWhere().getValue().get(0);
            assertThat(cond.getField()).isEqualTo("name");
            assertThat(cond.getOperator()).isEqualTo(FieldCondition.NE);
            assertThat(cond.getFuncChain()).containsExactly("substr(1,3)");
        }

        // 补点 W2b: 末段写 "eq" 会被当函数（当前实现的隐式行为，锁定语义）
        @Test
        void eqKeywordFallsIntoFuncChain() {
            var spec = builder.parse(new HashMap<>() {{
                put("name|substr(1,3)|eq", "abc");
            }}, null);
            var cond = (FieldCondition) spec.getWhere().getValue().get(0);
            assertThat(cond.getOperator()).isEqualTo(FieldCondition.EQ);
            assertThat(cond.getFuncChain()).containsExactly("substr(1,3)", "eq");
        }

        // 补点 W3: D4=末段隐式函数（无操作符，末段进链，EQ）
        @Test
        void funcChainImplicitEq() {
            var spec = builder.parse(new HashMap<>() {{
                put("tags|count", 5);
            }}, null);
            var cond = (FieldCondition) spec.getWhere().getValue().get(0);
            assertThat(cond.getOperator()).isEqualTo(FieldCondition.EQ);
            assertThat(cond.getFuncChain()).containsExactly("count");
            assertThat(cond.getValues()).containsExactly(5);
        }

        // 补点 W4: D2=List × 范围条件（between 传 List）
        @Test
        void betweenWithListValue() {
            var spec = builder.parse(new HashMap<>() {{
                put("age|between", List.of(18, 60));
            }}, null);
            var cond = (FieldCondition) spec.getWhere().getValue().get(0);
            assertThat(cond.getOperator()).isEqualTo(FieldCondition.BETWEEN);
            assertThat(cond.getValues()).containsExactly(18, 60);
        }

        // 补点 W5: D3=dot 路径 × 多段
        @Test
        void multiSegDotPath() {
            var spec = builder.parse(new HashMap<>() {{
                put("extra.a.b|lt", "1");
            }}, null);
            var cond = (FieldCondition) spec.getWhere().getValue().get(0);
            assertThat(cond.getField()).isEqualTo("extra.a.b");
            assertThat(cond.getOperator()).isEqualTo(FieldCondition.LT);
        }

        // 补点 W6: 元数据键（pg/lock/raw/p/pc/rt/od/lg）不进入 where
        @Test
        void metaKeysFiltered() {
            var spec = builder.parse(new HashMap<>() {{
                put("pg", 1);
                put("lock", true);
                put("raw", "x");
                put("rt", "id,name");
                put("od", "id");
                put("p", 1);
                put("pc", 10);
                put("lg", "(name=John)");
            }}, null);
            // 仅剩 lg 解析出的子树条件，元数据键不在顶层条件里
            assertThat(spec.getWhere().getValue()).hasSize(1);
        }
    }

    /**
     * 正交维度（lg 逻辑树解析）：
     *   D1 根类型:     1=AND  2=OR
     *   D2 嵌套深度:   1=单层  2=两层  3=三层
     *   D3 子项形态:   1=纯叶子  2=含 AND 子树  3=含 OR 子树
     *   D4 与外部条件: 1=无外部条件  2=外部条件与 lg 并存  3=外部条件与 lg 同键（去重）
     * 用例映射（L9 抽样 + 补点）：
     *   L1  1/1/1/1  (name=John,age=18)
     *   L2  2/1/1/1  !(name=John,age=18)
     *   L3  1/3/3/1  (a=1,(b=2,!(c=3,d=4)))
     *   L4  1/2/2/1  (a=1,(b=2,c=3)) — AND 下含 AND 子树…原情况 4 用 !(..) 表示 OR 子树
     *   L5  2/2/2/1  !(a=1,(b=2,c=3))
     *   L6  2/1/1/1  OR 三叶子
     *   L7  1/2/2/2  lg + 外部独立条件并存
     *   L8  1/2/2/3  lg 与外部同键 → 外部键被 lg 覆盖去重
     *   L9  2/2/3/1  !(a=1,!(b=2,c=3)) OR 下含 OR 子树
     */
    @Nested
    @DisplayName("lg 逻辑组解析")
    class LogicGroup {

        // L1: AND 单层
        @Test
        void simpleAnd() {
            var spec = builder.parse(new HashMap<>() {{
                put("lg", "(name=John,age=18)");
            }}, null);
            assertThat(spec.getWhere().getType()).isEqualTo(AND);
            assertThat(spec.getWhere().getValue()).hasSize(2);
        }

        // L2: OR 单层
        @Test
        void simpleOr() {
            var spec = builder.parse(new HashMap<>() {{
                put("lg", "!(name=John,age=18)");
            }}, null);
            assertThat(spec.getWhere().getType()).isEqualTo(OR);
            assertThat(spec.getWhere().getValue()).hasSize(2);
        }

        // L3: 三层嵌套
        @Test
        void threeLevelNested() {
            var spec = builder.parse(new HashMap<>() {{
                put("lg", "(a=1,(b=2,!(c=3,d=4)))");
            }}, null);
            assertThat(spec.getWhere().getType()).isEqualTo(AND);
            assertThat(spec.getWhere().getValue()).hasSize(2);
            var level2 = (LogicNode) spec.getWhere().getValue().get(1);
            assertThat(level2.getType()).isEqualTo(AND);
            assertThat(level2.getChildren()).hasSize(1);
            var level3 = level2.getChildren().get(0);
            assertThat(level3.getType()).isEqualTo(OR);
            assertThat(level3.getValue()).hasSize(2);
        }

        // L4: AND 下含 OR 子树
        @Test
        void andWithOrChild() {
            var spec = builder.parse(new HashMap<>() {{
                put("lg", "(a=1,!(b=2,c=3))");
            }}, null);
            assertThat(spec.getWhere().getType()).isEqualTo(AND);
            assertThat(spec.getWhere().getChildren()).hasSize(1);
            var orChild = spec.getWhere().getChildren().get(0);
            assertThat(orChild.getType()).isEqualTo(OR);
            assertThat(orChild.getValue()).hasSize(2);
        }

        // L5: OR 下含 AND 子树
        @Test
        void orWithAndChild() {
            var spec = builder.parse(new HashMap<>() {{
                put("lg", "!(a=1,(b=2,c=3))");
            }}, null);
            assertThat(spec.getWhere().getType()).isEqualTo(OR);
            assertThat(spec.getWhere().getChildren()).hasSize(1);
            var andChild = spec.getWhere().getChildren().get(0);
            assertThat(andChild.getType()).isEqualTo(AND);
            assertThat(andChild.getValue()).hasSize(2);
        }

        // L6: 单层 OR 多条件
        @Test
        void orMultipleLeaves() {
            var spec = builder.parse(new HashMap<>() {{
                put("lg", "!(status=active,status=pending,status=review)");
            }}, null);
            assertThat(spec.getWhere().getType()).isEqualTo(OR);
            assertThat(spec.getWhere().getValue()).hasSize(3);
        }

        // L7: lg 子树 + 外部独立条件 → 顶层 AND 包 lg 子树
        @Test
        void lgWithExternalConditions() {
            var spec = builder.parse(new HashMap<>() {{
                put("lg", "(name=John)");
                put("age|ge", "18");
            }}, null);
            assertThat(spec.getWhere().getType()).isEqualTo(AND);
            assertThat(spec.getWhere().getValue()).hasSize(2);
            assertThat(spec.getWhere().getChildren()).hasSize(1);
            var child = spec.getWhere().getChildren().get(0);
            assertThat(child.getValue()).hasSize(1);
            var fc = (FieldCondition) child.getValue().get(0);
            assertThat(fc.getField()).isEqualTo("name");
            assertThat(fc.getValues()).containsExactly("John");
        }

        // L8: lg 与外部同键 → 外部键被去重（lg 的值优先）
        @Test
        void lgKeyDedup() {
            var spec = builder.parse(new HashMap<>() {{
                put("lg", "(name=John)");
                put("name", "Moke");
                put("age|ge", "18");
            }}, null);
            assertThat(spec.getWhere().getType()).isEqualTo(AND);
            // 顶层仅 age 一个外部条件 + lg 子树
            assertThat(spec.getWhere().getValue()).hasSize(2);
            var subtree = (LogicNode) spec.getWhere().getValue().stream()
                    .filter(v -> v instanceof LogicNode).findFirst().orElseThrow();
            var nameCond = (FieldCondition) subtree.getValue().get(0);
            assertThat(nameCond.getValues()).containsExactly("John");
        }

        // L9: OR 下含 OR 子树
        @Test
        void orWithOrChild() {
            var spec = builder.parse(new HashMap<>() {{
                put("lg", "!(a=1,!(b=2,c=3))");
            }}, null);
            assertThat(spec.getWhere().getType()).isEqualTo(OR);
            assertThat(spec.getWhere().getChildren()).hasSize(1);
            assertThat(spec.getWhere().getChildren().get(0).getType()).isEqualTo(OR);
        }
    }

    /**
     * 正交维度（od 解析）：
     *   D1 值类型:   1=不传  2=String  3=List
     *   D2 方向:     1=升序(无前缀)  2=降序(- 前缀)  3=升降混合
     *   D3 字段个数: 1=0 个  2=1 个  3=多个
     *   D4 命名:     1=小写  2=驼峰
     * 用例映射：
     *   O1  1/-/1/-  null → 无排序
     *   O2  2/1/2/1  "name" asc
     *   O3  2/2/2/2  "-createTime" desc 驼峰
     *   O4  2/3/3/2  三字段混合方向
     *   O5  3/3/2/2  List 两字段
     *   O6  3/2/2/1  List 全降序
     *   O7  边界     非法类型 → IllegalStateException
     */
    @Nested
    @DisplayName("orderBy 解析")
    class OrderBy {

        // O1
        @Test
        void nullOd() {
            var spec = builder.parse(new HashMap<>(), null);
            assertThat(spec.getOrderBy()).isNull();
        }

        // O2
        @Test
        void singleAsc() {
            var spec = builder.parse(new HashMap<>() {{
                put("od", "name");
            }}, null);
            assertThat(spec.getOrderBy()).hasSize(1);
            assertThat(spec.getOrderBy().get(0).getField()).isEqualTo("name");
            assertThat(spec.getOrderBy().get(0).getDirection()).isEqualTo(OrderSpec.ASC);
        }

        // O3
        @Test
        void singleDescCamel() {
            var spec = builder.parse(new HashMap<>() {{
                put("od", "-createTime");
            }}, null);
            assertThat(spec.getOrderBy().get(0).getField()).isEqualTo("createTime");
            assertThat(spec.getOrderBy().get(0).getDirection()).isEqualTo(OrderSpec.DESC);
        }

        // O4
        @Test
        void mixedDirections() {
            var spec = builder.parse(new HashMap<>() {{
                put("od", "name,-createTime,email");
            }}, null);
            assertThat(spec.getOrderBy()).hasSize(3);
            assertThat(spec.getOrderBy().get(0).getDirection()).isEqualTo(OrderSpec.ASC);
            assertThat(spec.getOrderBy().get(1).getField()).isEqualTo("createTime");
            assertThat(spec.getOrderBy().get(1).getDirection()).isEqualTo(OrderSpec.DESC);
            assertThat(spec.getOrderBy().get(2).getDirection()).isEqualTo(OrderSpec.ASC);
        }

        // O5
        @Test
        void listMixed() {
            var spec = builder.parse(new HashMap<>() {{
                put("od", List.of("name", "-createTime"));
            }}, null);
            assertThat(spec.getOrderBy()).hasSize(2);
        }

        // O6
        @Test
        void allDesc() {
            var spec = builder.parse(new HashMap<>() {{
                put("od", "-name,-age");
            }}, null);
            assertThat(spec.getOrderBy()).allMatch(o -> o.getDirection() == OrderSpec.DESC);
        }

        // O7: 边界——非法类型
        @Test
        void invalidOdType() {
            assertThatThrownBy(() -> builder.parse(new HashMap<>() {{
                put("od", 123);
            }}, null)).isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * 正交维度（limit/offset 解析）：
     *   D1 pc 传参:  1=不传  2=String 数字  3=Integer 数字
     *   D2 p 传参:   1=不传  2=String       3=Integer
     *   D3 p 取值:   1=1     2=2            3=≥3
     * 用例映射（D1×D2×D3 抽样，limit/offset 联动全对覆盖）：
     *   N1  1/1/-  均不传 → limit null、offset null
     *   N2  2/1/-  仅 pc → limit 10、offset null
     *   N3  1/2/-  仅 p  → limit null、offset null
     *   N4  2/2/1  p1 pc10 → limit 10、offset 0
     *   N5  2/2/2  p2 pc10 → limit 10、offset 10
     *   N6  2/3/3  p3(String) pc20 → offset 40
     *   N7  3/3/≥3 p5 pc15(Integer) → offset 60
     *   N8  2/2/2  pc 决定 limit（与 p 无关）
     */
    @Nested
    @DisplayName("limit/offset 解析")
    class Pagination {

        // N1
        @Test
        void noPagination() {
            var spec = builder.parse(new HashMap<>(), null);
            assertThat(spec.getLimit()).isNull();
            assertThat(spec.getOffset()).isNull();
        }

        // N2
        @Test
        void onlyPc() {
            var spec = builder.parse(new HashMap<>() {{
                put("pc", "10");
            }}, null);
            assertThat(spec.getLimit()).isEqualTo(10);
            assertThat(spec.getOffset()).isNull();
        }

        // N3
        @Test
        void onlyP() {
            var spec = builder.parse(new HashMap<>() {{
                put("p", "2");
            }}, null);
            assertThat(spec.getLimit()).isNull();
            assertThat(spec.getOffset()).isNull();
        }

        // N4
        @Test
        void firstPage() {
            var spec = builder.parse(new HashMap<>() {{
                put("p", "1");
                put("pc", "10");
            }}, null);
            assertThat(spec.getOffset()).isEqualTo(0);
        }

        // N5
        @Test
        void secondPage() {
            var spec = builder.parse(new HashMap<>() {{
                put("p", "2");
                put("pc", "10");
            }}, null);
            assertThat(spec.getOffset()).isEqualTo(10);
        }

        // N6
        @Test
        void thirdPageStringTypes() {
            var spec = builder.parse(new HashMap<>() {{
                put("p", "3");
                put("pc", "20");
            }}, null);
            assertThat(spec.getOffset()).isEqualTo(40);
        }

        // N7
        @Test
        void integerTypes() {
            var spec = builder.parse(new HashMap<>() {{
                put("p", 5);
                put("pc", 15);
            }}, null);
            assertThat(spec.getOffset()).isEqualTo(60);
        }

        // N8: limit 由 pc 决定
        @Test
        void limitFromPcOnly() {
            var spec = builder.parse(new HashMap<>() {{
                put("pc", "5");
                put("p", "2");
            }}, null);
            assertThat(spec.getLimit()).isEqualTo(5);
        }
    }

    /**
     * 全因子联合：rt × where × lg 结构 × od × 分页一次解析全对。
     */
    @Nested
    @DisplayName("完整查询联合解析")
    class CompleteQuery {

        @Test
        void allFactors() {
            var query = new HashMap<String, Object>() {{
                put("rt", "id,name,email");
                put("name", "John");
                put("age|ge", "18");
                put("od", "name,-createTime");
                put("pc", "10");
                put("p", "2");
            }};

            var spec = builder.parse(query, null);

            assertThat(spec.getSelectFields()).containsExactly("id", "name", "email");
            assertThat(spec.getWhere().getValue()).hasSize(2);
            assertThat(spec.getOrderBy()).hasSize(2);
            assertThat(spec.getLimit()).isEqualTo(10);
            assertThat(spec.getOffset()).isEqualTo(10);
        }
    }

    /**
     * 正交补充组：非法输入与跨因子边界（不属于任何单因子的主表水平，作 pairwise 补点）：
     *   X1  lg 语法错误经 parse 透传 IllegalArgumentException
     *   X2  树形字符串渲染（LogicNode.toTreeString）与结构一致
     *   X3  IN 三值 + 混合类型入参
     *   X4  仅 "!()" 显式空 OR
     */
    @Nested
    @DisplayName("边界与补充组合")
    class Supplement {

        // X1
        @Test
        void lgInvalidFormatThrows() {
            assertThatThrownBy(() -> builder.parse(new HashMap<>() {{
                put("lg", "(abc)");
            }}, null)).isInstanceOf(IllegalArgumentException.class);
        }

        // X2
        @Test
        void treeStringRender() {
            var spec = builder.parse(new HashMap<>() {{
                put("lg", "(a=1,!(b=2,c=3))");
            }}, null);
            var tree = spec.getWhere().toTreeString();
            assertThat(tree).contains("AND").contains("OR").contains("=");
        }

        // X3
        @Test
        void inMixedTypeValues() {
            var spec = builder.parse(new HashMap<>() {{
                put("id|in", List.of(1, "2", 3));
            }}, null);
            var cond = (FieldCondition) spec.getWhere().getValue().get(0);
            assertThat(cond.getValues()).containsExactly(1, "2", 3);
        }

        // X4
        @Test
        void emptyOrTree() {
            var spec = builder.parse(new HashMap<>() {{
                put("lg", "!");
            }}, null);
            assertThat(spec.getWhere().getType()).isEqualTo(OR);
            assertThat(spec.getWhere().getValue()).isEmpty();
        }
    }
}
