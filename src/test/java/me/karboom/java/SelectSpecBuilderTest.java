package me.karboom.java;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SelectSpecBuilder.parse 方法测试类
 * 按照 SelectSpec 的成员变量分为 7 个测试函数
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
        var cond2 = (SelectSpecBuilder.FieldCondition) spec2.getWhere().getValue().get(0);
        assertThat(cond2.getField()).isEqualTo("name");
        assertThat(cond2.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.EQ);
        assertThat(cond2.getValues()).containsExactly("John");

        // 情况 3: 比较操作符 ge
        var query3 = new HashMap<String, Object>() {{
            put("age|ge", "18");
        }};
        var spec3 = builder.parse(query3, null);
        var cond3 = (SelectSpecBuilder.FieldCondition) spec3.getWhere().getValue().get(0);
        assertThat(cond3.getField()).isEqualTo("age");
        assertThat(cond3.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.GE);
        assertThat(cond3.getValues()).containsExactly("18");

        // 情况 4: 比较操作符 gt
        var query4 = new HashMap<String, Object>() {{
            put("age|gt", "18");
        }};
        var spec4 = builder.parse(query4, null);
        var cond4 = (SelectSpecBuilder.FieldCondition) spec4.getWhere().getValue().get(0);
        assertThat(cond4.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.GT);

        // 情况 5: 比较操作符 le
        var query5 = new HashMap<String, Object>() {{
            put("age|le", "60");
        }};
        var spec5 = builder.parse(query5, null);
        var cond5 = (SelectSpecBuilder.FieldCondition) spec5.getWhere().getValue().get(0);
        assertThat(cond5.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.LE);

        // 情况 6: 比较操作符 lt
        var query6 = new HashMap<String, Object>() {{
            put("age|lt", "60");
        }};
        var spec6 = builder.parse(query6, null);
        var cond6 = (SelectSpecBuilder.FieldCondition) spec6.getWhere().getValue().get(0);
        assertThat(cond6.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.LT);

        // 情况 7: 比较操作符 ne
        var query7 = new HashMap<String, Object>() {{
            put("status|ne", "deleted");
        }};
        var spec7 = builder.parse(query7, null);
        var cond7 = (SelectSpecBuilder.FieldCondition) spec7.getWhere().getValue().get(0);
        assertThat(cond7.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.NE);

        // 情况 8: IN 操作符，逗号分隔字符串
        var query8 = new HashMap<String, Object>() {{
            put("status|in", "active,pending");
        }};
        var spec8 = builder.parse(query8, null);
        var cond8 = (SelectSpecBuilder.FieldCondition) spec8.getWhere().getValue().get(0);
        assertThat(cond8.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.IN);
        assertThat(cond8.getValues()).containsExactly("active", "pending");

        // 情况 9: IN 操作符，List 类型
        var query9 = new HashMap<String, Object>() {{
            put("status|in", List.of("active", "pending"));
        }};
        var spec9 = builder.parse(query9, null);
        var cond9 = (SelectSpecBuilder.FieldCondition) spec9.getWhere().getValue().get(0);
        assertThat(cond9.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.IN);
        assertThat(cond9.getValues()).containsExactly("active", "pending");

        // 情况 10: NOT_IN 操作符
        var query10 = new HashMap<String, Object>() {{
            put("status|notIn", "deleted,archived");
        }};
        var spec10 = builder.parse(query10, null);
        var cond10 = (SelectSpecBuilder.FieldCondition) spec10.getWhere().getValue().get(0);
        assertThat(cond10.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.NOT_IN);

        // 情况 11: BETWEEN 操作符
        var query11 = new HashMap<String, Object>() {{
            put("age|between", "18,60");
        }};
        var spec11 = builder.parse(query11, null);
        var cond11 = (SelectSpecBuilder.FieldCondition) spec11.getWhere().getValue().get(0);
        assertThat(cond11.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.BETWEEN);
        assertThat(cond11.getValues()).containsExactly("18", "60");

        // 情况 12: NOT_BETWEEN 操作符
        var query12 = new HashMap<String, Object>() {{
            put("age|notBetween", "18,60");
        }};
        var spec12 = builder.parse(query12, null);
        var cond12 = (SelectSpecBuilder.FieldCondition) spec12.getWhere().getValue().get(0);
        assertThat(cond12.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.NOT_BETWEEN);

        // 情况 13: LIKE 操作符
        var query13 = new HashMap<String, Object>() {{
            put("name|like", "%John%");
        }};
        var spec13 = builder.parse(query13, null);
        var cond13 = (SelectSpecBuilder.FieldCondition) spec13.getWhere().getValue().get(0);
        assertThat(cond13.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.LIKE);

        // 情况 14: NOT_LIKE 操作符
        var query14 = new HashMap<String, Object>() {{
            put("name|notLike", "%test%");
        }};
        var spec14 = builder.parse(query14, null);
        var cond14 = (SelectSpecBuilder.FieldCondition) spec14.getWhere().getValue().get(0);
        assertThat(cond14.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.NOT_LIKE);

        // 情况 15: INTERSECT 操作符
        var query15 = new HashMap<String, Object>() {{
            put("tags|intersect", "java,spring");
        }};
        var spec15 = builder.parse(query15, null);
        var cond15 = (SelectSpecBuilder.FieldCondition) spec15.getWhere().getValue().get(0);
        assertThat(cond15.getOperator()).isEqualTo(SelectSpecBuilder.FieldCondition.Operator.INTERSECT);

        // 情况 16: 多个条件
        var query16 = new HashMap<String, Object>() {{
            put("name", "John");
            put("age|ge", "18");
            put("status", "active");
        }};
        var spec16 = builder.parse(query16, null);
        assertThat(spec16.getWhere().getValue()).hasSize(3);

        // 情况 17: 驼峰命名转下划线
        var query17 = new HashMap<String, Object>() {{
            put("createTime|ge", "2024-01-01");
        }};
        var spec17 = builder.parse(query17, null);
        var cond17 = (SelectSpecBuilder.FieldCondition) spec17.getWhere().getValue().get(0);
        assertThat(cond17.getField()).isEqualTo("create_time");

        // 情况 18: 逻辑树 - AND 条件
        var query18 = new HashMap<String, Object>() {{
            put("name", "John");
            put("age", "18");
            put("lg", "(name,age)");
        }};
        var spec18 = builder.parse(query18, null);
        assertThat(spec18.getWhere().getType()).isEqualTo("and");
        assertThat(spec18.getWhere().getValue()).hasSize(2);

        // 情况 19: 逻辑树 - OR 条件
        var query19 = new HashMap<String, Object>() {{
            put("name", "John");
            put("age", "18");
            put("lg", "!(name,age)");
        }};
        var spec19 = builder.parse(query19, null);
        assertThat(spec19.getWhere().getType()).isEqualTo("or");

        // 情况 20: 嵌套逻辑树
        var query20 = new HashMap<String, Object>() {{
            put("a", "1");
            put("b", "2");
            put("c", "3");
            put("lg", "(a,(b,c))");
        }};
        var spec20 = builder.parse(query20, null);
        assertThat(spec20.getWhere().getValue()).hasSize(2);
        assertThat(spec20.getWhere().getChildren()).hasSize(1);
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
        assertThat(spec2.getOrderBy().get(0).getDirection()).isEqualTo(SelectSpecBuilder.OrderSpec.Direction.ASC);

        // 情况 3: 降序排序（- 前缀）
        var query3 = new HashMap<String, Object>() {{
            put("od", "-createTime");
        }};
        var spec3 = builder.parse(query3, null);
        assertThat(spec3.getOrderBy()).hasSize(1);
        assertThat(spec3.getOrderBy().get(0).getField()).isEqualTo("createTime");
        assertThat(spec3.getOrderBy().get(0).getDirection()).isEqualTo(SelectSpecBuilder.OrderSpec.Direction.DESC);

        // 情况 4: 多个排序字段，逗号分隔
        var query4 = new HashMap<String, Object>() {{
            put("od", "name,-createTime,email");
        }};
        var spec4 = builder.parse(query4, null);
        assertThat(spec4.getOrderBy()).hasSize(3);
        assertThat(spec4.getOrderBy().get(0).getField()).isEqualTo("name");
        assertThat(spec4.getOrderBy().get(0).getDirection()).isEqualTo(SelectSpecBuilder.OrderSpec.Direction.ASC);
        assertThat(spec4.getOrderBy().get(1).getField()).isEqualTo("createTime");
        assertThat(spec4.getOrderBy().get(1).getDirection()).isEqualTo(SelectSpecBuilder.OrderSpec.Direction.DESC);
        assertThat(spec4.getOrderBy().get(2).getField()).isEqualTo("email");
        assertThat(spec4.getOrderBy().get(2).getDirection()).isEqualTo(SelectSpecBuilder.OrderSpec.Direction.ASC);

        // 情况 5: List 类型的排序字段
        var query5 = new HashMap<String, Object>() {{
            put("od", List.of("name", "-createTime"));
        }};
        var spec5 = builder.parse(query5, null);
        assertThat(spec5.getOrderBy()).hasSize(2);

        // 情况 6: 驼峰命名转下划线
        var query6 = new HashMap<String, Object>() {{
            put("od", "createTime");
        }};
        var spec6 = builder.parse(query6, null);
        assertThat(spec6.getOrderBy().get(0).getField()).isEqualTo("create_time");

        // 情况 7: 多个降序字段
        var query7 = new HashMap<String, Object>() {{
            put("od", "-name,-age");
        }};
        var spec7 = builder.parse(query7, null);
        assertThat(spec7.getOrderBy()).allMatch(o -> o.getDirection() == SelectSpecBuilder.OrderSpec.Direction.DESC);
    }

    /**
     * 测试 groupBy 分组解析
     */
    @Test
    void testParseGroupBy() {
        // 情况 1: gp 为 null，无分组
        var query1 = new HashMap<String, Object>();
        var spec1 = builder.parse(query1, null);
        assertThat(spec1.getGroupBy()).isNull();

        // 情况 2: 单个分组字段
        var query2 = new HashMap<String, Object>() {{
            put("gp", "type");
        }};
        var spec2 = builder.parse(query2, null);
        assertThat(spec2.getGroupBy()).containsExactly("type");

        // 情况 3: 多个分组字段，逗号分隔
        var query3 = new HashMap<String, Object>() {{
            put("gp", "type,status");
        }};
        var spec3 = builder.parse(query3, null);
        assertThat(spec3.getGroupBy()).containsExactly("type", "status");

        // 情况 4: List 类型的分组字段
        var query4 = new HashMap<String, Object>() {{
            put("gp", List.of("type", "status", "category"));
        }};
        var spec4 = builder.parse(query4, null);
        assertThat(spec4.getGroupBy()).containsExactly("type", "status", "category");

        // 情况 5: 驼峰命名转下划线
        var query5 = new HashMap<String, Object>() {{
            put("gp", "userType,createTime");
        }};
        var spec5 = builder.parse(query5, null);
        assertThat(spec5.getGroupBy()).containsExactly("user_type", "create_time");
    }

    /**
     * 测试 aggregations 聚合函数解析
     */
    @Test
    void testParseAggregations() {
        // 情况 1: 无聚合函数
        var query1 = new HashMap<String, Object>();
        var spec1 = builder.parse(query1, null);
        assertThat(spec1.getAggregations()).isNull();

        // 情况 2: 单个聚合函数，字段为字符串
        var query2 = new HashMap<String, Object>() {{
            put("count", "id");
        }};
        var spec2 = builder.parse(query2, null);
        assertThat(spec2.getAggregations()).hasSize(1);
        assertThat(spec2.getAggregations().get("count")).containsExactly("id");

        // 情况 3: 单个聚合函数，字段为 List
        var query3 = new HashMap<String, Object>() {{
            put("count", List.of("id", "name"));
        }};
        var spec3 = builder.parse(query3, null);
        assertThat(spec3.getAggregations().get("count")).containsExactly("id", "name");

        // 情况 4: 多个聚合函数
        var query4 = new HashMap<String, Object>() {{
            put("count", "id");
            put("sum", "amount");
            put("avg", "price");
        }};
        var spec4 = builder.parse(query4, null);
        assertThat(spec4.getAggregations()).hasSize(3);
        assertThat(spec4.getAggregations().get("count")).containsExactly("id");
        assertThat(spec4.getAggregations().get("sum")).containsExactly("amount");
        assertThat(spec4.getAggregations().get("avg")).containsExactly("price");

        // 情况 5: 聚合函数字段为 Set
        var query5 = new HashMap<String, Object>() {{
            put("count", new java.util.HashSet<>(List.of("id", "name")));
        }};
        var spec5 = builder.parse(query5, null);
        assertThat(spec5.getAggregations().get("count")).hasSize(2);
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
     * 测试 parseLogic 方法解析逻辑表达式（纯数字索引）
     */
    @Test
    void testParseLogic() {
        // ==================== 基础 AND 逻辑 ====================
        
        // 测试 (0,1) - 两个条件的 AND
        SelectSpecBuilder.LogicNode andTwo = builder.parseLogic("(0,1)");
        assertThat(andTwo.getType()).isEqualTo("and");
        assertThat(andTwo.getValue()).containsExactly("0", "1");
        assertThat(andTwo.getAllChild()).containsExactlyInAnyOrder("0", "1");
        assertThat(andTwo.getChildren()).isEmpty();

        // 测试 (0,1,2) - 多个条件的 AND
        SelectSpecBuilder.LogicNode andMultiple = builder.parseLogic("(0,1,2)");
        assertThat(andMultiple.getType()).isEqualTo("and");
        assertThat(andMultiple.getValue()).containsExactly("0", "1", "2");
        assertThat(andMultiple.getAllChild()).containsExactlyInAnyOrder("0", "1", "2");
        assertThat(andMultiple.getChildren()).isEmpty();

        // 测试 (0) - 单个条件的 AND
        SelectSpecBuilder.LogicNode andSingle = builder.parseLogic("(0)");
        assertThat(andSingle.getType()).isEqualTo("and");
        assertThat(andSingle.getValue()).containsExactly("0");
        assertThat(andSingle.getAllChild()).containsExactly("0");
        assertThat(andSingle.getChildren()).isEmpty();

        // ==================== 基础 OR 逻辑 ====================
        
        // 测试 !(0,1) - 两个条件的 OR
        SelectSpecBuilder.LogicNode orTwo = builder.parseLogic("!(0,1)");
        assertThat(orTwo.getType()).isEqualTo("or");
        assertThat(orTwo.getValue()).containsExactly("0", "1");
        assertThat(orTwo.getAllChild()).containsExactlyInAnyOrder("0", "1");
        assertThat(orTwo.getChildren()).isEmpty();

        // 测试 !(0,1,2) - 多个条件的 OR
        SelectSpecBuilder.LogicNode orMultiple = builder.parseLogic("!(0,1,2)");
        assertThat(orMultiple.getType()).isEqualTo("or");
        assertThat(orMultiple.getValue()).containsExactly("0", "1", "2");
        assertThat(orMultiple.getAllChild()).containsExactlyInAnyOrder("0", "1", "2");
        assertThat(orMultiple.getChildren()).isEmpty();

        // ==================== 嵌套逻辑 ====================
        
        // 测试 (0,(1,2)) - AND 嵌套 AND
        SelectSpecBuilder.LogicNode andNestedAnd = builder.parseLogic("(0,(1,2))");
        assertThat(andNestedAnd.getType()).isEqualTo("and");
        assertThat(andNestedAnd.getValue()).hasSize(2);
        assertThat(andNestedAnd.getChildren()).hasSize(1);
        assertThat(andNestedAnd.getAllChild()).containsExactlyInAnyOrder("0", "1", "2");
        
        SelectSpecBuilder.LogicNode childAnd = andNestedAnd.getChildren().get(0);
        assertThat(childAnd.getType()).isEqualTo("and");
        assertThat(childAnd.getValue()).containsExactly("1", "2");

        // 测试 (0,!(1,2)) - AND 嵌套 OR
        SelectSpecBuilder.LogicNode andNestedOr = builder.parseLogic("(0,!(1,2))");
        assertThat(andNestedOr.getType()).isEqualTo("and");
        assertThat(andNestedOr.getValue()).hasSize(2);
        assertThat(andNestedOr.getChildren()).hasSize(1);
        
        SelectSpecBuilder.LogicNode childOr = andNestedOr.getChildren().get(0);
        assertThat(childOr.getType()).isEqualTo("or");
        assertThat(childOr.getValue()).containsExactly("1", "2");

        // 测试 !(0,(1,2)) - OR 嵌套 AND
        SelectSpecBuilder.LogicNode orNestedAnd = builder.parseLogic("!(0,(1,2))");
        assertThat(orNestedAnd.getType()).isEqualTo("or");
        assertThat(orNestedAnd.getValue()).hasSize(2);
        assertThat(orNestedAnd.getChildren()).hasSize(1);
        
        SelectSpecBuilder.LogicNode childNestedAnd = orNestedAnd.getChildren().get(0);
        assertThat(childNestedAnd.getType()).isEqualTo("and");
        assertThat(childNestedAnd.getValue()).containsExactly("1", "2");

        // 测试 !(0,!(1,2)) - OR 嵌套 OR
        SelectSpecBuilder.LogicNode orNestedOr = builder.parseLogic("!(0,!(1,2))");
        assertThat(orNestedOr.getType()).isEqualTo("or");
        assertThat(orNestedOr.getValue()).hasSize(2);
        assertThat(orNestedOr.getChildren()).hasSize(1);
        
        SelectSpecBuilder.LogicNode childNestedOr = orNestedOr.getChildren().get(0);
        assertThat(childNestedOr.getType()).isEqualTo("or");
        assertThat(childNestedOr.getValue()).containsExactly("1", "2");

        // ==================== 边界情况 ====================
        
        // 测试 ! - 空的 OR（应该变成 !()）
        SelectSpecBuilder.LogicNode emptyOr = builder.parseLogic("!");
        assertThat(emptyOr.getType()).isEqualTo("or");
//        assertThat(emptyOr.getValue()).isEmpty();

        // 测试 () - 空的 AND
        SelectSpecBuilder.LogicNode emptyAnd = builder.parseLogic("()");
        assertThat(emptyAnd.getType()).isEqualTo("and");
//        assertThat(emptyAnd.getValue()).isEmpty();

        // ==================== 复杂嵌套 ====================
        
        // 测试 ((0,1),(2,3)) - 多层嵌套 AND
        SelectSpecBuilder.LogicNode complexAnd = builder.parseLogic("((0,1),(2,3))");
        assertThat(complexAnd.getType()).isEqualTo("and");
        assertThat(complexAnd.getChildren()).hasSize(2);
        assertThat(complexAnd.getAllChild()).containsExactlyInAnyOrder("0", "1", "2", "3");
        
        assertThat(complexAnd.getChildren().get(0).getValue()).containsExactly("0", "1");
        assertThat(complexAnd.getChildren().get(1).getValue()).containsExactly("2", "3");

        // 测试 (0,!(1,!(2,3))) - 深度嵌套
        SelectSpecBuilder.LogicNode deepNested = builder.parseLogic("(0,!(1,!(2,3)))");
        assertThat(deepNested.getType()).isEqualTo("and");
        assertThat(deepNested.getValue()).hasSize(2);
        assertThat(deepNested.getAllChild()).containsExactlyInAnyOrder("0", "1", "2", "3");
        
        SelectSpecBuilder.LogicNode level1Or = deepNested.getChildren().get(0);
        assertThat(level1Or.getType()).isEqualTo("or");
        assertThat(level1Or.getValue()).hasSize(2);
        
        SelectSpecBuilder.LogicNode level2Or = level1Or.getChildren().get(0);
        assertThat(level2Or.getType()).isEqualTo("or");
        assertThat(level2Or.getValue()).containsExactly("2", "3");
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
            put("gp", "type");
            put("count", "id");
            put("pc", "10");
            put("p", "2");
        }};

        var spec = builder.parse(query, null);

        // 验证所有字段
        assertThat(spec.getSelectFields()).containsExactly("id", "name", "email");
        assertThat(spec.getWhere().getValue()).hasSize(2);
        assertThat(spec.getOrderBy()).hasSize(2);
        assertThat(spec.getGroupBy()).containsExactly("type");
        assertThat(spec.getAggregations()).hasSize(1);
        assertThat(spec.getAggregations().get("count")).containsExactly("id");
        assertThat(spec.getLimit()).isEqualTo(10);
        assertThat(spec.getOffset()).isEqualTo(10);
    }
}