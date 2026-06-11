package me.karboom.java.filaments;

import me.karboom.java.filaments.spec.FieldCondition;
import me.karboom.java.filaments.spec.LogicNode;
import me.karboom.java.filaments.spec.OrderSpec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static me.karboom.java.filaments.spec.LogicNode.AND;
import static me.karboom.java.filaments.spec.LogicNode.OR;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SelectSpecBuilder.parse 方法测试类
 * 覆盖 selectFields、where（含逻辑组 lg）、orderBy、limit、offset 及完整查询
 */
public class SelectSpecBuilderTest {

    private final SelectSpecBuilder builder = new SelectSpecBuilder();

    /**
     * 测试 selectFields 字段选择解析
     */
    @Test
    void testParseSelectFields() {
        // 情况 1: rt 为 null，应该返回 null（SELECT *）
        var query1 = new HashMap<String, Object>();
        var spec1 = builder.parse(query1, null);
        assertThat(spec1.getSelectFields()).isNull();

        // 情况 2: rt 为逗号分隔的字符串
        var query2 = new HashMap<String, Object>() {{
            put("rt", "id,name,email");
        }};
        var spec2 = builder.parse(query2, null);
        assertThat(spec2.getSelectFields()).containsExactly("id", "name", "email");

        // 情况 3: rt 为 List 类型
        var query3 = new HashMap<String, Object>() {{
            put("rt", List.of("id", "name", "extra"));
        }};
        var spec3 = builder.parse(query3, null);
        assertThat(spec3.getSelectFields()).containsExactly("id", "name", "extra");

        // 情况 4: rt 为单个字段字符串
        var query4 = new HashMap<String, Object>() {{
            put("rt", "id");
        }};
        var spec4 = builder.parse(query4, null);
        assertThat(spec4.getSelectFields()).containsExactly("id");

        // 情况 5: rt 包含驼峰命名（应该保持原样）
        var query5 = new HashMap<String, Object>() {{
            put("rt", "userId,createTime");
        }};
        var spec5 = builder.parse(query5, null);
        assertThat(spec5.getSelectFields()).containsExactly("userId", "createTime");
    }

    /**
     * 测试 where 条件解析
     */
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

    /**
     * 测试 where 逻辑组 (lg) 解析
     */
    @Test
    void testParseWhereLogicGroup() {
        // 情况 1: 逻辑树 - AND 条件
        var query1 = new HashMap<String, Object>() {{
            put("lg", "(name=John,age=18)");
        }};
        var spec1 = builder.parse(query1, null);
        System.out.println("=== 情况1: AND 条件 ===");
        System.out.println(spec1.getWhere().toTreeString());
        assertThat(spec1.getWhere().getType()).isEqualTo(AND);
        assertThat(spec1.getWhere().getValue()).hasSize(2);

        // 情况 2: 逻辑树 - OR 条件
        var query2 = new HashMap<String, Object>() {{
            put("lg", "!(name=John,age=18)");
        }};
        var spec2 = builder.parse(query2, null);
        System.out.println("=== 情况2: OR 条件 ===");
        System.out.println(spec2.getWhere().toTreeString());
        assertThat(spec2.getWhere().getType()).isEqualTo(OR);
        assertThat(spec2.getWhere().getValue()).hasSize(2);

        // 情况 3: 多层嵌套，三层逻辑树
        var query3 = new HashMap<String, Object>() {{
            put("lg", "(a=1,(b=2,!(c=3,d=4)))");
        }};
        var spec3 = builder.parse(query3, null);
        System.out.println("=== 情况3: 三层嵌套 ===");
        System.out.println(spec3.getWhere().toTreeString());
        assertThat(spec3.getWhere().getType()).isEqualTo(AND);
        assertThat(spec3.getWhere().getValue()).hasSize(2);
        // 第二层: (b=2, !(c=3,d=4)) — AND 节点，含 1 个 OR 子节点
        var level2 = (LogicNode) spec3.getWhere().getValue().get(1);
        assertThat(level2.getType()).isEqualTo(AND);
        assertThat(level2.getChildren()).hasSize(1);
        // 第三层: !(c=3,d=4) — OR 节点
        var level3 = level2.getChildren().get(0);
        assertThat(level3.getType()).isEqualTo(OR);
        assertThat(level3.getValue()).hasSize(2);

        // 情况 4: 混合 AND 下含 OR 子树
        var query4 = new HashMap<String, Object>() {{
            put("lg", "(a=1,!(b=2,c=3))");
        }};
        var spec4 = builder.parse(query4, null);
        System.out.println("=== 情况4: AND 下含 OR ===");
        System.out.println(spec4.getWhere().toTreeString());
        assertThat(spec4.getWhere().getType()).isEqualTo(AND);
        assertThat(spec4.getWhere().getChildren()).hasSize(1);
        var orChild = spec4.getWhere().getChildren().get(0);
        assertThat(orChild.getType()).isEqualTo(OR);
        assertThat(orChild.getValue()).hasSize(2);

        // 情况 5: OR 下含 AND 子树
        var query5 = new HashMap<String, Object>() {{
            put("lg", "!(a=1,(b=2,c=3))");
        }};
        var spec5 = builder.parse(query5, null);
        System.out.println("=== 情况5: OR 下含 AND ===");
        System.out.println(spec5.getWhere().toTreeString());
        assertThat(spec5.getWhere().getType()).isEqualTo(OR);
        assertThat(spec5.getWhere().getChildren()).hasSize(1);
        var andChild = spec5.getWhere().getChildren().get(0);
        assertThat(andChild.getType()).isEqualTo(AND);
        assertThat(andChild.getValue()).hasSize(2);

        // 情况 6: 单层 OR 多条件
        var query6 = new HashMap<String, Object>() {{
            put("lg", "!(status=active,status=pending,status=review)");
        }};
        var spec6 = builder.parse(query6, null);
        System.out.println("=== 情况6: OR 多条件 ===");
        System.out.println(spec6.getWhere().toTreeString());
        assertThat(spec6.getWhere().getType()).isEqualTo(OR);
        assertThat(spec6.getWhere().getValue()).hasSize(3);
    }

    /**
     * 测试 orderBy 排序解析
     */
    @Test
    void testParseOrderBy() {
        // 情况 1: od 为 null，无排序
        var query1 = new HashMap<String, Object>();
        var spec1 = builder.parse(query1, null);
        assertThat(spec1.getOrderBy()).isNull();

        // 情况 2: 升序排序（无前缀）
        var query2 = new HashMap<String, Object>() {{
            put("od", "name");
        }};
        var spec2 = builder.parse(query2, null);
        assertThat(spec2.getOrderBy()).hasSize(1);
        assertThat(spec2.getOrderBy().get(0).getField()).isEqualTo("name");
        assertThat(spec2.getOrderBy().get(0).getDirection()).isEqualTo(OrderSpec.ASC);

        // 情况 3: 降序排序（- 前缀）
        var query3 = new HashMap<String, Object>() {{
            put("od", "-createTime");
        }};
        var spec3 = builder.parse(query3, null);
        assertThat(spec3.getOrderBy()).hasSize(1);
        assertThat(spec3.getOrderBy().get(0).getField()).isEqualTo("createTime");
        assertThat(spec3.getOrderBy().get(0).getDirection()).isEqualTo(OrderSpec.DESC);

        // 情况 4: 多个排序字段，逗号分隔
        var query4 = new HashMap<String, Object>() {{
            put("od", "name,-createTime,email");
        }};
        var spec4 = builder.parse(query4, null);
        assertThat(spec4.getOrderBy()).hasSize(3);
        assertThat(spec4.getOrderBy().get(0).getField()).isEqualTo("name");
        assertThat(spec4.getOrderBy().get(0).getDirection()).isEqualTo(OrderSpec.ASC);
        assertThat(spec4.getOrderBy().get(1).getField()).isEqualTo("createTime");
        assertThat(spec4.getOrderBy().get(1).getDirection()).isEqualTo(OrderSpec.DESC);
        assertThat(spec4.getOrderBy().get(2).getField()).isEqualTo("email");
        assertThat(spec4.getOrderBy().get(2).getDirection()).isEqualTo(OrderSpec.ASC);

        // 情况 5: List 类型的排序字段
        var query5 = new HashMap<String, Object>() {{
            put("od", List.of("name", "-createTime"));
        }};
        var spec5 = builder.parse(query5, null);
        assertThat(spec5.getOrderBy()).hasSize(2);

        // 情况 6: 驼峰命名保持原样
        var query6 = new HashMap<String, Object>() {{
            put("od", "createTime");
        }};
        var spec6 = builder.parse(query6, null);
        assertThat(spec6.getOrderBy().get(0).getField()).isEqualTo("createTime");

        // 情况 7: 多个降序字段
        var query7 = new HashMap<String, Object>() {{
            put("od", "-name,-age");
        }};
        var spec7 = builder.parse(query7, null);
        assertThat(spec7.getOrderBy()).allMatch(o -> o.getDirection() == OrderSpec.DESC);
    }

    /**
     * 测试 limit 限制数量解析
     */
    @Test
    void testParseLimit() {
        // 情况 1: 无 pc 参数，limit 为 null
        var query1 = new HashMap<String, Object>();
        var spec1 = builder.parse(query1, null);
        assertThat(spec1.getLimit()).isNull();

        // 情况 2: pc 为整数
        var query2 = new HashMap<String, Object>() {{
            put("pc", "10");
        }};
        var spec2 = builder.parse(query2, null);
        assertThat(spec2.getLimit()).isEqualTo(10);

        // 情况 3: pc 为字符串数字
        var query3 = new HashMap<String, Object>() {{
            put("pc", 20);
        }};
        var spec3 = builder.parse(query3, null);
        assertThat(spec3.getLimit()).isEqualTo(20);

        // 情况 4: pc 和 p 同时存在（limit 由 pc 决定）
        var query4 = new HashMap<String, Object>() {{
            put("pc", "5");
            put("p", "2");
        }};
        var spec4 = builder.parse(query4, null);
        assertThat(spec4.getLimit()).isEqualTo(5);
    }

    /**
     * 测试 offset 偏移量解析
     */
    @Test
    void testParseOffset() {
        // 情况 1: 无 p 和 pc 参数，offset 为 null
        var query1 = new HashMap<String, Object>();
        var spec1 = builder.parse(query1, null);
        assertThat(spec1.getOffset()).isNull();

        // 情况 2: 只有 p 参数，无 pc 参数，offset 为 null
        var query2 = new HashMap<String, Object>() {{
            put("p", "2");
        }};
        var spec2 = builder.parse(query2, null);
        assertThat(spec2.getOffset()).isNull();

        // 情况 3: p=1, pc=10, offset 应该为 0
        var query3 = new HashMap<String, Object>() {{
            put("p", "1");
            put("pc", "10");
        }};
        var spec3 = builder.parse(query3, null);
        assertThat(spec3.getOffset()).isEqualTo(0);

        // 情况 4: p=2, pc=10, offset 应该为 10
        var query4 = new HashMap<String, Object>() {{
            put("p", "2");
            put("pc", "10");
        }};
        var spec4 = builder.parse(query4, null);
        assertThat(spec4.getOffset()).isEqualTo(10);

        // 情况 5: p=3, pc=20, offset 应该为 40
        var query5 = new HashMap<String, Object>() {{
            put("p", "3");
            put("pc", "20");
        }};
        var spec5 = builder.parse(query5, null);
        assertThat(spec5.getOffset()).isEqualTo(40);

        // 情况 6: p 为整数类型
        var query6 = new HashMap<String, Object>() {{
            put("p", 5);
            put("pc", 15);
        }};
        var spec6 = builder.parse(query6, null);
        assertThat(spec6.getOffset()).isEqualTo(60);
    }



    /**
     * 测试完整查询参数解析
     */
    @Test
    void testParseCompleteQuery() {
        var query = new HashMap<String, Object>() {{
            put("rt", "id,name,email");
            put("name", "John");
            put("age|ge", "18");
            put("od", "name,-createTime");
            put("pc", "10");
            put("p", "2");
        }};

        var spec = builder.parse(query, null);

        // 验证所有字段
        assertThat(spec.getSelectFields()).containsExactly("id", "name", "email");
        assertThat(spec.getWhere().getValue()).hasSize(2);
        assertThat(spec.getOrderBy()).hasSize(2);
        assertThat(spec.getLimit()).isEqualTo(10);
        assertThat(spec.getOffset()).isEqualTo(10);
    }
}