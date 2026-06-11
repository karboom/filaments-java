package me.karboom.java.filaments.spec;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 逻辑树节点
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogicNode {
    public static final String AND = "and";
    public static final String OR = "or";

    public String type = AND;
    public List<Object> value = new ArrayList<>();
    public String str = "";
    public List<String> allChild = new ArrayList<>();
    public List<LogicNode> children = new ArrayList<>();

    /**
     * 将逻辑树以树形字符串输出，便于调试和阅读
     */
    public String toTreeString() {
        var sb = new StringBuilder();
        buildTreeString(sb, "", "");
        return sb.toString();
    }

    private void buildTreeString(StringBuilder sb, String prefix, String childPrefix) {
        sb.append(childPrefix).append(type.toUpperCase()).append("\n");

        for (int i = 0; i < value.size(); i++) {
            boolean isLast = (i == value.size() - 1);
            String connector = isLast ? "└── " : "├── ";
            String nextPrefix = prefix + (isLast ? "    " : "│   ");

            Object item = value.get(i);
            if (item instanceof LogicNode child) {
                child.buildTreeString(sb, nextPrefix, prefix + connector);
            } else if (item instanceof FieldCondition fc) {
                sb.append(prefix).append(connector)
                        .append(fc.getField()).append(" ").append(operatorSymbol(fc.getOperator()))
                        .append(" ").append(fc.getValues()).append("\n");
            } else if (item instanceof String s) {
                sb.append(prefix).append(connector).append(s).append("\n");
            }
        }
    }

    private static String operatorSymbol(String op) {
        return switch (op) {
            case FieldCondition.EQ -> "=";
            case FieldCondition.NE -> "<>";
            case FieldCondition.GT -> ">";
            case FieldCondition.GE -> ">=";
            case FieldCondition.LT -> "<";
            case FieldCondition.LE -> "<=";
            case FieldCondition.IN -> "IN";
            case FieldCondition.NOT_IN -> "NOT IN";
            case FieldCondition.BETWEEN -> "BETWEEN";
            case FieldCondition.NOT_BETWEEN -> "NOT BETWEEN";
            case FieldCondition.LIKE -> "LIKE";
            case FieldCondition.NOT_LIKE -> "NOT LIKE";
            case FieldCondition.INTERSECT -> "INTERSECT";
            default -> op;
        };
    }
}
