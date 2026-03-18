package me.karboom.java;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 查询规范构建器
 * 负责将 Map 参数解析为结构化 SelectSpec
 * 只负责语法解析，不涉及具体 ORM 实现
 */
public class SelectSpecBuilder {

    // ==================== 静态常量 ====================

    public static final String NAME_SPLITTER = "|";

    // ==================== 数据类 ====================

    /**
     * 查询规范对象 - 结构化表示
     */
    @Data
    public static class SelectSpec {
        private List<String> selectFields;      // 返回字段，null 表示 SELECT *
        private LogicNode where;                // WHERE 条件树
        private List<OrderSpec> orderBy;        // ORDER BY
        private List<String> groupBy;           // GROUP BY
        private Map<String, List<String>> aggregations; // 聚合 {函数：字段}
        private Integer limit;
        private Integer offset;
    }

    /**
     * 排序规则
     */
    @Data
    @Builder
    public static class OrderSpec {
        public enum Direction { ASC, DESC }

        private String field;
        private Direction direction;
    }

    /**
     * 逻辑树节点
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogicNode {

        public String type = "and";
        public List<Object> value = new ArrayList<>();
        public String str = "";
        public List<String> allChild = new ArrayList<>();
        public List<LogicNode> children = new ArrayList<>();


    }

    /**
     * 条件表达式
     */
    @Data
    public static class FieldCondition {
        public enum Operator {
            EQ, NE, GT, GE, LT, LE,
            IN, NOT_IN,
            BETWEEN, NOT_BETWEEN,
            LIKE, NOT_LIKE,
            INTERSECT
        }

        private String field;
        private Operator operator;
        private List<Object> values;
        private List<String> funcChain;
    }

    // ==================== 成员变量 ====================


    // ==================== 公开方法 ====================

    /**
     * 解析为 SelectSpec（用于调试/序列化）
     */
    public SelectSpec parse(Map<String, Object> query, String subQueryAlias) {
        SelectSpec spec = new SelectSpec();

        parseSelectFields(query, spec);
        parseOrderBy(query, spec);
        parseWhere(query, spec);
        parseGroupBy(query, spec);
        parseAggregations(query, spec);
        parsePagination(query, spec);

        return spec;
    }

    // ==================== 解析方法 ====================

    private void parseSelectFields(Map<String, Object> query, SelectSpec spec) {
        var rt = query.get("rt");
        var list = new ArrayList<String>();

        if (rt != null) {
            switch (rt) {
                case String s -> list.addAll(StrUtil.split(s, ","));
                case List<?> l -> list.addAll(l.stream().map(Object::toString).toList());
                default -> throw new IllegalStateException("Unexpected value: " + rt);
            }
            spec.setSelectFields(list);
        }
    }

    private void parseOrderBy(Map<String, Object> query, SelectSpec spec) {
        var od = query.get("od");
        var list = new ArrayList<String>();

        switch (od) {
            case String s -> list.addAll(StrUtil.split(s, ","));
            case List<?> l -> list.addAll(l.stream().map(Object::toString).toList());
            case null -> {}
            default -> throw new IllegalStateException("Unexpected value: " + od);
        }

        var orderByList = new ArrayList<OrderSpec>();
        for (String part : list) {
            part = StrUtil.toUnderlineCase(part);
            if (part.startsWith("-")) {
                orderByList.add(OrderSpec.builder()
                        .field(part.substring(1))
                        .direction(OrderSpec.Direction.DESC)
                        .build());
            } else {
                orderByList.add(OrderSpec.builder()
                        .field(part)
                        .direction(OrderSpec.Direction.ASC)
                        .build());
            }
        }
        spec.setOrderBy(orderByList);
    }

    private void parseWhere(Map<String, Object> query, SelectSpec spec) {
        var filteredQuery = new HashMap<>(query);
        filteredQuery.keySet().removeAll(List.of("p", "pc", "od", "rt", "gp", "pg", "lg", "lock"));

        LogicNode logicTree = LogicNode.builder()
                .type("and")
                .value(new ArrayList<>())
                .allChild(new ArrayList<>())
                .children(new ArrayList<>())
                .build();;
        var lg = (String) query.get("lg");
        if (lg != null) {
            logicTree = parseLogic(lg);
        }

        var logicTreeDefaultValue = new HashMap<>(filteredQuery);
        logicTreeDefaultValue.keySet().removeAll(logicTree.getAllChild());
        logicTree.getValue().addAll(logicTreeDefaultValue.keySet());

        // 将条件解析为 FieldCondition 对象
        parseLogicTreeConditions(logicTree, filteredQuery);

        spec.setWhere(logicTree);
    }

    private void parseLogicTreeConditions(LogicNode node, Map<String, Object> query) {
        for (int i = 0; i < node.getValue().size(); i++) {
            Object item = node.getValue().get(i);
            if (item instanceof String) {
                String key = (String) item;
                var pickedQuery = new HashMap<>(query);
                pickedQuery.keySet().retainAll(List.of(key));
                FieldCondition cond = buildFieldCondition(pickedQuery, key);
                node.getValue().set(i, cond);
            } else if (item instanceof LogicNode) {
                parseLogicTreeConditions((LogicNode) item, query);
            }
        }
    }

    private void parsePagination(Map<String, Object> query, SelectSpec spec) {
        if (query.containsKey("pc")) {
            int pc = Integer.parseInt(query.get("pc").toString());
            spec.setLimit(pc);
            if (query.containsKey("p")) {
                int p = Integer.parseInt(query.get("p").toString());
                spec.setOffset((p - 1) * pc);
            }
        }
    }

    private void parseGroupBy(Map<String, Object> query, SelectSpec spec) {
        var gp = query.get("gp");
        var list = new ArrayList<String>();

        switch (gp) {
            case String s -> list.addAll(StrUtil.split(s, ","));
            case List<?> l -> list.addAll(l.stream().map(Object::toString).toList());
            case null -> {}
            default -> throw new IllegalStateException("Unexpected value: " + gp);
        }

        var groupByList = new ArrayList<String>();
        for (String part : list) {
            groupByList.add(StrUtil.toUnderlineCase(part));
        }
        if (!groupByList.isEmpty()) {
            spec.setGroupBy(groupByList);
        }
    }

    private void parseAggregations(Map<String, Object> query, SelectSpec spec) {
        var aggregationKeys = List.of("count", "sum", "avg", "min", "max", "countDistinct");
        var aggregations = new HashMap<String, List<String>>();

        for (String key : aggregationKeys) {
            if (query.containsKey(key)) {
                var value = query.get(key);
                var fieldList = new ArrayList<String>();

                switch (value) {
                    case String s -> fieldList.add(StrUtil.toUnderlineCase(s));
                    case List<?> l -> fieldList.addAll(l.stream().map(Object::toString).map(StrUtil::toUnderlineCase).toList());
                    case Set<?> s -> fieldList.addAll(s.stream().map(Object::toString).map(StrUtil::toUnderlineCase).toList());
                    default -> throw new IllegalStateException("Unexpected value: " + value);
                }
                aggregations.put(key, fieldList);
            }
        }

        if (!aggregations.isEmpty()) {
            spec.setAggregations(aggregations);
        }
    }

    // ==================== 条件解析方法 ====================

    private FieldCondition buildFieldCondition(Map<String, Object> pickedQuery, String key) {
        var value = buildValue(pickedQuery.get(key));

        var leftParts = StrUtil.split(key, NAME_SPLITTER);
        var funcList = new ArrayList<>(leftParts).subList(1, leftParts.size());
        var fieldName = StrUtil.toUnderlineCase(leftParts.get(0));

        FieldCondition cond = new FieldCondition();
        cond.setField((fieldName));
        cond.setFuncChain(funcList);
        cond.setValues(Arrays.asList(value));

        String lastPart = leftParts.getLast();

        switch (lastPart) {
            case "ne" -> cond.setOperator(FieldCondition.Operator.NE);
            case "ge" -> cond.setOperator(FieldCondition.Operator.GE);
            case "gt" -> cond.setOperator(FieldCondition.Operator.GT);
            case "le" -> cond.setOperator(FieldCondition.Operator.LE);
            case "lt" -> cond.setOperator(FieldCondition.Operator.LT);
            case "in" -> cond.setOperator(FieldCondition.Operator.IN);
            case "notIn" -> cond.setOperator(FieldCondition.Operator.NOT_IN);
            case "between" -> cond.setOperator(FieldCondition.Operator.BETWEEN);
            case "notBetween" -> cond.setOperator(FieldCondition.Operator.NOT_BETWEEN);
            case "like" -> cond.setOperator(FieldCondition.Operator.LIKE);
            case "notLike" -> cond.setOperator(FieldCondition.Operator.NOT_LIKE);
            case "intersect" -> cond.setOperator(FieldCondition.Operator.INTERSECT);
            default -> cond.setOperator(FieldCondition.Operator.EQ);
        }

        return cond;
    }

    // ==================== 辅助方法 ====================

    /**
     * 字符串内容为查询key的索引从0开始计算
     * @param input
     * @return
     */
    public LogicNode parseLogic(String input) {
        if (input.equals("!")) {
            input = "!()";
        }

        LogicNode root = new LogicNode();
        LogicNode current = root;
        Stack<LogicNode> stack = new Stack<>();
        stack.push(root);

        for (int i = 0; i < input.length(); i++) {
            char charAt = input.charAt(i);

            if (charAt == '(') {
                char prev = i > 0 ? input.charAt(i - 1) : '\0';
                String type = prev == '!' ? "or" : "and";
                LogicNode node = new LogicNode();
                node.type = type;

                current.children.add(node);
                current.str += "?" + (current.children.size() - 1);

                stack.push(node);
                current = node;
            } else if (charAt == ')') {
                Set<String> all_child = new HashSet<>();
                for (String key : current.str.split(",")) {
                    if (key.startsWith("?")) {
                        int index = Integer.parseInt(key.substring(1));
                        current.value.add(current.children.get(index));

                        for (LogicNode child : current.children) {
                            all_child.addAll(child.allChild);
                        }
                    } else {
                        all_child.add(key);
                        current.value.add(key);
                    }
                }
                current.allChild = new ArrayList<>(all_child);

                stack.pop();
                current = stack.peek();
            } else {
                if (charAt != '!') {
                    current.str += charAt;
                }
            }
        }

        return root.children.isEmpty() ? root : root.children.get(0);
    }

    public String buildHolder(Object[] value, String splitter) {
        return Stream.of(value)
                .map(_ -> "?")
                .collect(Collectors.joining(" " + splitter + " "));
    }

    public Object[] buildValue(Object value) {
        switch (value) {
            case List<?> l -> {
                return l.toArray();
            }
            case String s -> {
                return StrUtil.split(s, ",").toArray();
            }
            case Set<?> s -> {
                return s.toArray();
            }
            default -> {
                return new Object[]{value};
            }
        }
    }

    public String buildFuncChain(String sqlField, List<String> parts) {
        for (String func : parts) {
            List<String> paramList = new ArrayList<>();
            if (func.contains("(")) {
                int startIndex = func.indexOf('(') + 1;
                int endIndex = func.indexOf(')');
                if (startIndex < endIndex) {
                    paramList = List.of(func.substring(startIndex, endIndex).split(","));
                }
                func = func.substring(0, func.indexOf('('));
            }

            String paramStr = paramList.isEmpty() ? "" : ", " + String.join(",", paramList);
            sqlField = func + "(" + sqlField + paramStr + ")";
        }

        return sqlField;
    }
}
