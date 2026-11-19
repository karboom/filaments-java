package me.karboom.java.filaments;

import me.karboom.java.filaments.spec.FieldCondition;
import me.karboom.java.filaments.spec.LogicNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * SelectSpecBuilder：parse / parseLogic / buildValue / buildHolder / buildFuncChain / buildFieldCondition。
 *
 * <pre>
 * 正交表（parseLogic：lg 表达式形态）：
 * ┌────────────────────┬──────────────────────────┐
 * │ 输入               │ 预期                     │
 * ├────────────────────┼──────────────────────────┤
 * │ !(name,age|gt)     │ OR 节点，2 个裸 token    │
 * │ !(a,(b,c))         │ OR 嵌套 AND 子节点       │
 * │ !                  │ 空 OR（特例展开）        │
 * │ !(name,age|gt      │ 缺少右括号，带位置报错   │
 * │ name)              │ 右括号多余，带位置报错   │
 * │ (a)(b)             │ 多个顶层括号组报错       │
 * └────────────────────┴──────────────────────────┘
 * </pre>
 *
 * <pre>
 * 正交表（buildFieldCondition：条件 key 形态）：
 * ┌──────────────────┬──────────┬──────────────┬──────────┐
 * │ key              │ field    │ funcChain    │ operator │
 * ├──────────────────┼──────────┼──────────────┼──────────┤
 * │ name             │ name     │ []           │ EQ       │
 * │ name|lower       │ name     │ [lower]      │ EQ       │
 * │ name|ge          │ name     │ []           │ GE       │
 * │ name|lower|ge    │ name     │ [lower]      │ GE       │
 * │ address.city|gt  │ address.city│ []        │ GT       │
 * │ al::name|eq      │ name     │ []           │ EQ       │
 * └──────────────────┴──────────┴──────────────┴──────────┘
 * </pre>
 */
@Timeout(5)
class SelectSpecBuilderTest {

    SelectSpecBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SelectSpecBuilder();
    }

    // region parse -> SelectSpec

    @Nested
    class Parse {

        @Test
        void selectFieldsFromRt() {
            var spec = builder.parse(new LinkedHashMap<>(Map.of("rt", "id,name")));
            assertThat(spec.getSelectFields()).containsExactly("id", "name");
        }

        @Test
        void selectFieldsFromList() {
            var spec = builder.parse(new LinkedHashMap<>(Map.of("rt", List.of("id", "name"))));
            assertThat(spec.getSelectFields()).containsExactly("id", "name");
        }

        @Test
        void orderByParsesDescPrefix() {
            var spec = builder.parse(new LinkedHashMap<>(Map.of("od", "name,-age")));
            var od = spec.getOrderBy();
            assertAll(
                    () -> assertThat(od.get(0).getField()).isEqualTo("name"),
                    () -> assertThat(od.get(0).getDirection()).isEqualTo("ASC"),
                    () -> assertThat(od.get(1).getField()).isEqualTo("age"),
                    () -> assertThat(od.get(1).getDirection()).isEqualTo("DESC"));
        }

        @Test
        void paginationOffsetComputed() {
            var spec = builder.parse(new LinkedHashMap<>(Map.of("pc", 10, "p", 3)));
            assertAll(
                    () -> assertThat(spec.getLimit()).isEqualTo(10),
                    () -> assertThat(spec.getOffset()).isEqualTo(20));
        }

        @Test
        void paginationOnlyPc() {
            var spec = builder.parse(new LinkedHashMap<>(Map.of("pc", 5)));
            assertAll(
                    () -> assertThat(spec.getLimit()).isEqualTo(5),
                    () -> assertThat(spec.getOffset()).isNull());
        }

        @Test
        void paginationOffsetOverflowThrows() {
            // (p-1)*pc 在 long 域仍会溢出：必须在渲染进 SQL 之前带参数报错
            var q = new LinkedHashMap<String, Object>();
            q.put("pc", 2L);
            q.put("p", Long.MAX_VALUE);
            assertThatThrownBy(() -> builder.parse(q))
                    .isInstanceOf(FilamentsError.class)
                    .hasMessageContaining("分页偏移溢出")
                    .hasMessageContaining("p=" + Long.MAX_VALUE)
                    .hasCauseInstanceOf(ArithmeticException.class);
        }

        @Test
        void paginationOffsetNearMaxNoOverflow() {
            // p 极大但 pc=1：(p-1)*1 不溢出，应原样进 offset
            var spec = builder.parse(new LinkedHashMap<>(Map.of("pc", 1L, "p", Long.MAX_VALUE)));
            assertThat(spec.getOffset()).isEqualTo(Long.MAX_VALUE - 1);
        }

        @Test
        void reservedKeysExcludedFromWhere() {
            var q = new LinkedHashMap<String, Object>();
            q.put("rt", "id,name");
            q.put("od", "-age");
            q.put("pc", 10);
            q.put("p", 1);
            q.put("pg", true);
            q.put("name", "x");
            var spec = builder.parse(q);
            assertThat(spec.getWhere().getValue()).hasSize(1); // 仅 name 进 where
        }

        @Test
        void invalidRtTypeThrows() {
            var q = new LinkedHashMap<String, Object>();
            q.put("rt", 123);
            assertThatThrownBy(() -> builder.parse(q))
                    .isInstanceOf(FilamentsError.class)
                    .hasMessageContaining("Unexpected value");
        }
    }

    // endregion

    // region parseLogic（括号配对）

    @Nested
    class ParseLogic {

        @Test
        void orGroupParsesBareTokens() {
            var node = builder.parseLogic("!(name,age|gt)");
            assertAll(
                    () -> assertThat(node.type).isEqualTo(LogicNode.OR),
                    () -> assertThat(node.value).containsExactly("name", "age|gt"));
        }

        @Test
        void nestedGroupKeptAsChildReference() {
            var node = builder.parseLogic("!(a,(b,c))");
            var child = node.children.get(0);
            assertAll(
                    () -> assertThat(node.type).isEqualTo(LogicNode.OR),
                    () -> assertThat(child.type).isEqualTo(LogicNode.AND),
                    () -> assertThat(node.value).containsExactly("a", child),
                    () -> assertThat(child.value).containsExactly("b", "c"));
        }

        @Test
        void bangShorthandExpandsToEmptyOr() {
            var node = builder.parseLogic("!");
            assertAll(
                    () -> assertThat(node.type).isEqualTo(LogicNode.OR),
                    () -> assertThat(node.value).isEmpty());
        }

        @Test
        void unclosedParenThrowsWithPosition() {
            // 漏右括号若放行，括号内容进不了 value，! 的 OR 语义会被静默降级为顶层 AND
            assertThatThrownBy(() -> builder.parseLogic("!(name,age|gt"))
                    .isInstanceOf(FilamentsError.class)
                    .hasMessageContaining("缺少右括号")
                    .hasMessageContaining("位置 1");
        }

        @Test
        void extraParenThrowsWithPosition() {
            assertThatThrownBy(() -> builder.parseLogic("name)"))
                    .isInstanceOf(FilamentsError.class)
                    .hasMessageContaining("右括号多余")
                    .hasMessageContaining("位置 4");
        }

        @Test
        void multipleTopLevelGroupsRejected() {
            assertThatThrownBy(() -> builder.parseLogic("(a)(b)"))
                    .isInstanceOf(FilamentsError.class)
                    .hasMessageContaining("多个顶层括号组");
        }
    }

    // endregion

    // region buildValue / buildHolder

    @Nested
    class BuildValueHolder {

        @Test
        void commaStringSplit() {
            assertThat(builder.buildValue("1,2,3")).containsExactly("1", "2", "3");
        }

        @Test
        void listKept() {
            assertThat(builder.buildValue(List.of(1, 2))).containsExactly(1, 2);
        }

        @Test
        void setToArray() {
            assertThat(builder.buildValue(new LinkedSet("a", "b"))).containsExactlyInAnyOrder("a", "b");
        }

        @Test
        void scalarWrapped() {
            assertThat(builder.buildValue(42)).containsExactly(42);
        }

        @Test
        void holderComma() {
            // joining 用 " " + splitter + " " → "? , ? , ?"
            assertThat(builder.buildHolder(new Object[]{1, 2, 3}, ","))
                    .isEqualTo("? , ? , ?");
        }

        @Test
        void holderAnd() {
            assertThat(builder.buildHolder(new Object[]{1, 2}, "and"))
                    .isEqualTo("? and ?");
        }
    }

    // endregion

    // region buildFuncChain

    @Nested
    class FuncChain {

        @Test
        void wrapsFieldInOrder() {
            assertThat(builder.buildFuncChain("\"name\"", List.of("lower", "trim")))
                    .isEqualTo("trim(lower(\"name\"))");
        }

        @Test
        void numericParamAllowed() {
            assertThat(builder.buildFuncChain("\"x\"", List.of("substr(1,2)")))
                    .isEqualTo("substr(\"x\", 1,2)");
        }

        @Test
        void emptyChainPassthrough() {
            assertThat(builder.buildFuncChain("\"x\"", List.of())).isEqualTo("\"x\"");
        }

        @Test
        void unknownFuncRejected() {
            assertThatThrownBy(() -> builder.buildFuncChain("\"x\"", List.of("pg_advisory_lock")))
                    .isInstanceOf(FilamentsError.class)
                    .hasMessageContaining("白名单");
        }

        @Test
        void nonNumericParamRejected() {
            assertThatThrownBy(() -> builder.buildFuncChain("\"x\"", List.of("substr(a,2)")))
                    .isInstanceOf(FilamentsError.class)
                    .hasMessageContaining("数字字面量");
        }

        @Test
        void unclosedParamsRejected() {
            // 漏右括号曾把参数静默截掉：substr(1,2 → substr("x")
            assertThatThrownBy(() -> builder.buildFuncChain("\"x\"", List.of("substr(1,2")))
                    .isInstanceOf(FilamentsError.class)
                    .hasMessageContaining("括号不配对");
        }

        @Test
        void danglingOpenParenRejected() {
            // lower( 曾渲染成 lower("x")，括号与残渣一起被吞
            assertThatThrownBy(() -> builder.buildFuncChain("\"x\"", List.of("lower(")))
                    .isInstanceOf(FilamentsError.class)
                    .hasMessageContaining("括号不配对");
        }

        @Test
        void trailingAfterCloseRejected() {
            assertThatThrownBy(() -> builder.buildFuncChain("\"x\"", List.of("substr(1,2)y")))
                    .isInstanceOf(FilamentsError.class)
                    .hasMessageContaining("括号不配对");
        }

        @Test
        void emptyParensStillAllowed() {
            assertThat(builder.buildFuncChain("\"x\"", List.of("lower()")))
                    .isEqualTo("lower(\"x\")");
        }
    }

    // endregion

    // region buildFieldCondition（key 正交）

    @Nested
    class FieldConditionParse {

        private FieldCondition fc(String key, Object value) {
            return builder.buildFieldCondition(Map.of(key, value), key);
        }

        @Test
        void plainField() {
            var c = fc("name", "x");
            assertAll(
                    () -> assertThat(c.getField()).isEqualTo("name"),
                    () -> assertThat(c.getOperator()).isEqualTo(FieldCondition.EQ),
                    () -> assertThat(c.getFuncChain()).isEmpty(),
                    () -> assertThat(c.getValues()).containsExactly("x"));
        }

        @Test
        void funcOnly() {
            var c = fc("name|lower", "x");
            assertAll(
                    () -> assertThat(c.getOperator()).isEqualTo(FieldCondition.EQ),
                    () -> assertThat(c.getFuncChain()).containsExactly("lower"));
        }

        @Test
        void operatorOnly() {
            var c = fc("name|ge", 5);
            assertAll(
                    () -> assertThat(c.getOperator()).isEqualTo(FieldCondition.GE),
                    () -> assertThat(c.getFuncChain()).isEmpty());
        }

        @Test
        void funcThenOperator() {
            var c = fc("name|lower|ge", 5);
            assertAll(
                    () -> assertThat(c.getOperator()).isEqualTo(FieldCondition.GE),
                    () -> assertThat(c.getFuncChain()).containsExactly("lower"));
        }

        @Test
        void dotFieldKeepsPath() {
            var c = fc("address.city|gt", "a");
            assertAll(
                    () -> assertThat(c.getField()).isEqualTo("address.city"),
                    () -> assertThat(c.getOperator()).isEqualTo(FieldCondition.GT));
        }

        @Test
        void aliasPrefixStripped() {
            var c = fc("al::name|eq", "x");
            assertAll(
                    () -> assertThat(c.getField()).isEqualTo("name"),
                    () -> assertThat(c.getOperator()).isEqualTo(FieldCondition.EQ));
        }

        @Test
        void betweenOperatorMapped() {
            assertThat(fc("age|between", "1,10").getOperator()).isEqualTo(FieldCondition.BETWEEN);
        }

        @Test
        void intersectOperatorMapped() {
            assertThat(fc("tags|intersect", "a,b").getOperator()).isEqualTo(FieldCondition.INTERSECT);
        }

        @Test
        void commaStringKeptWhole() {
            // 解析期不拆逗号：多值简写保持一个元素，形态由渲染期按操作符决定
            assertThat(fc("id|in", "1,2").getValues()).containsExactly("1,2");
        }

        @Test
        void collectionValueKeptWhole() {
            // 解析期一个元素都不动：集合本身作为唯一元素收着，展开在渲染期按操作符做
            assertThat(fc("name|in", List.of("张,三", "user-2")).getValues())
                    .containsExactly(List.of("张,三", "user-2"));
            assertThat(fc("name|in", new LinkedSet("a", "b")).getValues())
                    .singleElement().isInstanceOf(LinkedSet.class);
        }

        @Test
        void nullValueKeptAsSingleNull() {
            var key = "name|isNull";
            var query = new LinkedHashMap<String, Object>();
            query.put(key, null);
            assertThat(builder.buildFieldCondition(query, key).getValues())
                    .containsExactly((Object) null);
        }
    }

    // endregion

    /** 保序 Set，用于验证 buildValue 对 Set 的处理 */
    static class LinkedSet extends LinkedHashSet<Object> {
        LinkedSet(Object... values) {
            addAll(List.of(values));
        }
    }
}
