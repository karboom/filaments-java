package me.karboom.java.filaments.spec;

import lombok.Data;

import java.util.List;

/**
 * 条件表达式
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

    private String field;
    private String operator;
    private List<Object> values;
    private List<String> funcChain;
}
