package me.karboom.java.filaments;

import me.karboom.java.filaments.spec.LogicNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * parseLogic：逻辑树解析（无连接，纯字符串）。
 *
 * <pre>
 * 正交表：{节点类型} × {嵌套深度} × {叶子形态}
 * ┌────┬──────────┬──────────┬──────────────┬──────────────────────┐
 * │ #  │ 输入      │ 期望类型 │ 期望结构      │ 说明                  │
 * ├────┼──────────┼──────────┼──────────────┼──────────────────────┤
 * │ 1  │ (a,b)    │ and      │ 2 个裸 token │ 顶层 AND             │
 * │ 2  │ !(a,b)   │ or       │ 2 个裸 token │ ! 前缀 → OR          │
 * │ 3  │ a=1,b    │ 报错      │ —            │ 内联值已禁用          │
 * │ 4  │ (a,(b,c))│ and      │ 深度2，含子 and │ 嵌套             │
 * │ 5  │ !(a,(b,c))│ or      │ 深度2，含子 and │ ! + 嵌套          │
 * │ 6  │ (x::a,b) │ and      │ 别名 token    │ 带冒号别名引用        │
 * │ 7  │ !        │ or       │ 空 value      │ "!" → "!()"          │
 * │ 8  │ ((a,b))  │ and      │ 深度2 同类型  │ 双层 AND             │
 * │ 9  │ (a)      │ and      │ 1 个 token    │ 单子节点             │
 * │ 10 │ a        │ and      │ 1 个裸 token │ 顶层裸 token 不丢     │
 * │ 11 │ a,(b,c)  │ and      │ token + 子树  │ 顶层混合按 AND 组合   │
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
        void combo3_inlineKeyValueRejected() {
            // lg 只做参数键的组合逻辑，值一律走查询参数
            assertThatThrownBy(() -> builder.parseLogic("(a=1,b)"))
                    .isInstanceOf(FilamentsError.class)
                    .hasMessageContaining("不支持内联值");
            assertThatThrownBy(() -> builder.parseLogic("a=1"))
                    .isInstanceOf(FilamentsError.class)
                    .hasMessageContaining("不支持内联值");
        }

        @Test
        void combo9_singleChild() {
            var node = builder.parseLogic("(a)");
            assertThat(node.getValue()).containsExactly("a");
        }

        @Test
        void combo10_topLevelBareTokenNotLost() {
            var node = builder.parseLogic("a");
            assertAll(
                    () -> assertThat(node.getType()).isEqualTo(LogicNode.AND),
                    () -> assertThat(node.getValue()).containsExactly("a"),
                    () -> assertThat(node.getAllChild()).containsExactly("a"));
        }

        @Test
        void topLevelBareTokensAnd() {
            var node = builder.parseLogic("a,b");
            assertThat(node.getValue()).containsExactly("a", "b");
        }
    }

    @Nested
    class TopLevelMixed {

        @Test
        void combo11_bareTokenWithGroup() {
            var root = builder.parseLogic("a,(b,c)");
            var child = (LogicNode) root.getValue().get(1);
            assertAll(
                    () -> assertThat(root.getType()).isEqualTo(LogicNode.AND),
                    () -> assertThat(root.getValue().get(0)).isEqualTo("a"),
                    () -> assertThat(child.getType()).isEqualTo(LogicNode.AND),
                    () -> assertThat(child.getValue()).containsExactly("b", "c"));
        }

        @Test
        void pureGroupStillReturnsSingleChildNoWrapper() {
            // 只有一个顶层组时不额外包一层 AND
            var root = builder.parseLogic("!(a,b)");
            assertThat(root.getType()).isEqualTo(LogicNode.OR);
            assertThat(root.getValue()).containsExactly("a", "b");
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
