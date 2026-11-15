package me.karboom.java.filaments;

import me.karboom.java.filaments.spec.FieldCondition;
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
 * SelectSpecBuilder：parse / buildValue / buildHolder / buildFuncChain / buildFieldCondition。
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
            var spec = builder.parse(new LinkedHashMap<>(Map.of("rt", "id,name")), null);
            assertThat(spec.getSelectFields()).containsExactly("id", "name");
        }

        @Test
        void selectFieldsFromList() {
            var spec = builder.parse(new LinkedHashMap<>(Map.of("rt", List.of("id", "name"))), null);
            assertThat(spec.getSelectFields()).containsExactly("id", "name");
        }

        @Test
        void orderByParsesDescPrefix() {
            var spec = builder.parse(new LinkedHashMap<>(Map.of("od", "name,-age")), null);
            var od = spec.getOrderBy();
            assertAll(
                    () -> assertThat(od.get(0).getField()).isEqualTo("name"),
                    () -> assertThat(od.get(0).getDirection()).isEqualTo("ASC"),
                    () -> assertThat(od.get(1).getField()).isEqualTo("age"),
                    () -> assertThat(od.get(1).getDirection()).isEqualTo("DESC"));
        }

        @Test
        void paginationOffsetComputed() {
            var spec = builder.parse(new LinkedHashMap<>(Map.of("pc", 10, "p", 3)), null);
            assertAll(
                    () -> assertThat(spec.getLimit()).isEqualTo(10),
                    () -> assertThat(spec.getOffset()).isEqualTo(20));
        }

        @Test
        void paginationOnlyPc() {
            var spec = builder.parse(new LinkedHashMap<>(Map.of("pc", 5)), null);
            assertAll(
                    () -> assertThat(spec.getLimit()).isEqualTo(5),
                    () -> assertThat(spec.getOffset()).isNull());
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
            var spec = builder.parse(q, null);
            assertThat(spec.getWhere().getValue()).hasSize(1); // 仅 name 进 where
        }

        @Test
        void invalidRtTypeThrows() {
            var q = new LinkedHashMap<String, Object>();
            q.put("rt", 123);
            assertThatThrownBy(() -> builder.parse(q, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unexpected value");
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
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("白名单");
        }

        @Test
        void nonNumericParamRejected() {
            assertThatThrownBy(() -> builder.buildFuncChain("\"x\"", List.of("substr(a,2)")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("数字字面量");
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
    }

    // endregion

    /** 保序 Set，用于验证 buildValue 对 Set 的处理 */
    static class LinkedSet extends LinkedHashSet<Object> {
        LinkedSet(Object... values) {
            addAll(List.of(values));
        }
    }
}
