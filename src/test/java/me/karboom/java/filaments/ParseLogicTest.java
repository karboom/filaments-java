package me.karboom.java.filaments;

import me.karboom.java.filaments.spec.LogicNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * parseLogic：逻辑树解析（无连接，纯字符串）。
 *
 * <pre>
 * 正交表 L9(3^3)：{节点类型} × {嵌套深度} × {叶子形态}
 * ┌────┬──────────┬──────────┬──────────────┬──────────────────────┐
 * │ #  │ 输入      │ 期望类型 │ 期望结构      │ 说明                  │
 * ├────┼──────────┼──────────┼──────────────┼──────────────────────┤
 * │ 1  │ (a,b)    │ and      │ 2 个裸 token │ 顶层 AND             │
 * │ 2  │ !(a,b)   │ or       │ 2 个裸 token │ ! 前缀 → OR          │
 * │ 3  │ a=1,b    │ and      │ k=v 内联      │ 内联赋值             │
 * │ 4  │ (a,(b,c))│ and      │ 深度2，含子 and │ 嵌套             │
 * │ 5  │ !(a,(b,c))│ or      │ 深度2，含子 and │ ! + 嵌套          │
 * │ 6  │ (x::a,b) │ and      │ 别名 token    │ 带冒号别名引用        │
 * │ 7  │ !        │ or       │ 空 value      │ "!" → "!()"          │
 * │ 8  │ ((a,b))  │ and      │ 深度2 同类型  │ 双层 AND             │
 * │ 9  │ (a)      │ and      │ 1 个 token    │ 单子节点             │
 * └────┴──────────┴──────────┴──────────────┴──────────────────────┘
 * </pre>
 */
@Timeout(5)
class ParseLogicTest {

    SelectSpecBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SelectSpecBuilder();
    }

    @Nested
    class FlatNodes {

        @Test
        void combo1_and() {
            var node = builder.parseLogic("(a,b)");
            assertAll(
                    () -> assertThat(node.getType()).isEqualTo(LogicNode.AND),
                    () -> assertThat(node.getValue()).containsExactly("a", "b"));
        }

        @Test
        void combo2_or() {
            var node = builder.parseLogic("!(a,b)");
            assertAll(
                    () -> assertThat(node.getType()).isEqualTo(LogicNode.OR),
                    () -> assertThat(node.getValue()).containsExactly("a", "b"));
        }

        @Test
        void combo3_inlineKeyValue() {
            var node = builder.parseLogic("(a=1,b)");
            var kv = (String[]) node.getValue().get(0);
            assertAll(
                    () -> assertThat(kv).containsExactly("a", "1"),
                    () -> assertThat(node.getValue().get(1)).isEqualTo("b"));
        }

        @Test
        void combo9_singleChild() {
            var node = builder.parseLogic("(a)");
            assertThat(node.getValue()).containsExactly("a");
        }
    }

    @Nested
    class NestedNodes {

        @Test
        void combo4_andWithChild() {
            var root = builder.parseLogic("(a,(b,c))");
            var child = (LogicNode) root.getValue().get(1);
            assertAll(
                    () -> assertThat(root.getType()).isEqualTo(LogicNode.AND),
                    () -> assertThat(root.getValue().get(0)).isEqualTo("a"),
                    () -> assertThat(child.getType()).isEqualTo(LogicNode.AND),
                    () -> assertThat(child.getValue()).containsExactly("b", "c"));
        }

        @Test
        void combo5_orWithChild() {
            var root = builder.parseLogic("!(a,(b,c))");
            var child = (LogicNode) root.getValue().get(1);
            assertAll(
                    () -> assertThat(root.getType()).isEqualTo(LogicNode.OR),
                    () -> assertThat(child.getType()).isEqualTo(LogicNode.AND));
        }

        @Test
        void combo8_doubleAnd() {
            var root = builder.parseLogic("((a,b))");
            var inner = (LogicNode) root.getValue().getFirst();
            assertAll(
                    () -> assertThat(root.getType()).isEqualTo(LogicNode.AND),
                    () -> assertThat(inner.getValue()).containsExactly("a", "b"));
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void combo6_aliasToken() {
            var node = builder.parseLogic("(x::a,b)");
            // 别名段 "x::a" 作为裸 token 原样入队（真正寻址在 parse 阶段）
            assertThat(node.getValue()).containsExactly("x::a", "b");
        }

        @Test
        void combo7_bangOnly() {
            var node = builder.parseLogic("!");
            assertAll(
                    () -> assertThat(node.getType()).isEqualTo(LogicNode.OR),
                    () -> assertThat(node.getValue()).isEmpty());
        }

        @Test
        void allChildCollectsLeafKeys() {
            var node = builder.parseLogic("(a,(b,c))");
            assertThat(node.getAllChild()).contains("a");
        }

        @Test
        void treeStringRendersStructure() {
            var node = builder.parseLogic("(a,b)");
            assertThat(node.toTreeString()).contains("AND").contains("a").contains("b");
        }
    }
}
