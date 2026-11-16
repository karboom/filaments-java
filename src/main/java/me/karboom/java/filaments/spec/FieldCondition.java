package me.karboom.java.filaments.spec;

import lombok.Data;

import java.util.List;

/**
 * 条件表达式：解析期产物，值保持原样形态。
 *
 * <p>{@link #values} 存的是「键上绑的那个东西」本身，不是最终绑定序列：解析期永远只收
 * 一个元素（{@code Map} 值是什么就收什么，{@code List}/{@code Set} 也不展开），
 * 要不要按逗号拆、要几个值，由渲染期按操作符的占位符个数决定
 * （{@code Filaments#buildConditionFromFieldCondition}）。
 * 因此 {@code LogicNode#toTreeString()} 里 {@code in -> [1,2]} 显示的是原始值而非拆分结果。
 */
@Data
public class FieldCondition {
    public static final String EQ = "EQ";
    public static final String NE = "NE";
    public static final String GT = "GT";
    public static final String GE = "GE";
    public static final String LT = "LT";
    public static final String LE = "LE";
    public static final String IN = "IN";
    public static final String NOT_IN = "NOT_IN";
    public static final String BETWEEN = "BETWEEN";
    public static final String NOT_BETWEEN = "NOT_BETWEEN";
    public static final String LIKE = "LIKE";
    public static final String NOT_LIKE = "NOT_LIKE";
    public static final String INTERSECT = "INTERSECT";
    public static final String IS_NULL = "IS_NULL";
    public static final String IS_NOT_NULL = "IS_NOT_NULL";

    private String field;
    private String operator;
    private List<Object> values;
    private List<String> funcChain;
}
