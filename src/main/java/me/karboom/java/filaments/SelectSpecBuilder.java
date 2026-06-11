package me.karboom.java.filaments;

import cn.hutool.core.util.StrUtil;
import me.karboom.java.filaments.spec.FieldCondition;
import me.karboom.java.filaments.spec.LogicNode;
import me.karboom.java.filaments.spec.OrderSpec;
import me.karboom.java.filaments.spec.SelectSpec;

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
            if (part.startsWith("-")) {
                orderByList.add(OrderSpec.builder()
                        .field(part.substring(1))
                        .direction(OrderSpec.DESC)
                        .build());
            } else {
                orderByList.add(OrderSpec.builder()
                        .field(part)
                        .direction(OrderSpec.ASC)
                        .build());
            }
        }
        if (!orderByList.isEmpty()) {
            spec.setOrderBy(orderByList);
        }
    }

    private void parseWhere(Map<String, Object> query, SelectSpec spec) {
        var filteredQuery = new HashMap<>(query);
        filteredQuery.keySet().removeAll(List.of("p", "pc", "od", "rt", "pg", "lg", "lock", "raw"));

        // 解析 lg 子树
        LogicNode lgTree = null;
        var lg = (String) query.get("lg");
        if (lg != null) {
            lgTree = parseLogic(lg);
        }

        // 如果只有 lg 没有其他条件，直接使用 lgTree
        if (lgTree != null && filteredQuery.isEmpty()) {
            parseLogicTreeConditions(lgTree, filteredQuery);
            spec.setWhere(lgTree);
            return;
        }

        // 创建顶层 AND 节点，lg 作为子树
        LogicNode topNode = LogicNode.builder()
                .type(LogicNode.AND)
                .value(new ArrayList<>())
                .allChild(new ArrayList<>())
                .children(new ArrayList<>())
                .build();

        // 添加非 lg 覆盖的查询参数
        Set<String> lgKeys = lgTree != null ? new HashSet<>(lgTree.getAllChild()) : Set.of();
        for (String key : filteredQuery.keySet()) {
            if (!lgKeys.contains(key)) {
                topNode.getValue().add(key);
            }
        }
        topNode.getAllChild().addAll(filteredQuery.keySet());
        if (lgTree != null) {
            topNode.getAllChild().addAll(lgTree.getAllChild());
        }

        // 将 lg 子树加入顶层节点
        if (lgTree != null) {
            topNode.getValue().add(lgTree);
            topNode.getChildren().add(lgTree);
        }

        parseLogicTreeConditions(topNode, filteredQuery);
        spec.setWhere(topNode);
    }

    private void parseLogicTreeConditions(LogicNode node, Map<String, Object> query) {
        for (int i = 0; i < node.getValue().size(); i++) {
            Object item = node.getValue().get(i);
            if (item instanceof String[]) {
                String[] kv = (String[]) item;
                FieldCondition cond = buildFieldCondition(Map.of(kv[0], (Object) kv[1]), kv[0]);
                node.getValue().set(i, cond);
            } else if (item instanceof String) {
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

    // ==================== 条件解析方法 ====================

    public FieldCondition buildFieldCondition(Map<String, Object> pickedQuery, String key) {
        var value = buildValue(pickedQuery.get(key));

        var leftParts = StrUtil.split(key, NAME_SPLITTER);
        var fieldName = leftParts.get(0);

        FieldCondition cond = new FieldCondition();
        cond.setField(fieldName);
        cond.setValues(Arrays.asList(value));

        String lastPart = leftParts.getLast();
        boolean isOperator = true;

        switch (lastPart) {
            case "ne" -> cond.setOperator(FieldCondition.NE);
            case "ge" -> cond.setOperator(FieldCondition.GE);
            case "gt" -> cond.setOperator(FieldCondition.GT);
            case "le" -> cond.setOperator(FieldCondition.LE);
            case "lt" -> cond.setOperator(FieldCondition.LT);
            case "in" -> cond.setOperator(FieldCondition.IN);
            case "notIn" -> cond.setOperator(FieldCondition.NOT_IN);
            case "between" -> cond.setOperator(FieldCondition.BETWEEN);
            case "notBetween" -> cond.setOperator(FieldCondition.NOT_BETWEEN);
            case "like" -> cond.setOperator(FieldCondition.LIKE);
            case "notLike" -> cond.setOperator(FieldCondition.NOT_LIKE);
            case "intersect" -> cond.setOperator(FieldCondition.INTERSECT);
            default -> { cond.setOperator(FieldCondition.EQ); isOperator = false; }
        }

        // Exclude operator from funcChain; for EQ without explicit operator, keep all parts
        if (isOperator && leftParts.size() > 1) {
            cond.setFuncChain(new ArrayList<>(leftParts).subList(1, leftParts.size() - 1));
        } else {
            cond.setFuncChain(new ArrayList<>(leftParts).subList(1, leftParts.size()));
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
                String type = prev == '!' ? LogicNode.OR : LogicNode.AND;
                LogicNode node = new LogicNode();
                node.type = type;

                current.children.add(node);
                current.str += "?" + (current.children.size() - 1);

                stack.push(node);
                current = node;
            } else if (charAt == ')') {
                Set<String> all_child = new HashSet<>();
                for (String key : current.str.split(",")) {
                    if (key.isEmpty()) continue;
                    if (key.startsWith("?")) {
                        int index = Integer.parseInt(key.substring(1));
                        current.value.add(current.children.get(index));

                        for (LogicNode child : current.children) {
                            all_child.addAll(child.allChild);
                        }
                    } else {
                        int eqIdx = key.indexOf('=');
                        if (eqIdx == -1) {
                            throw new IllegalArgumentException("lg expression item must be in k=v format, got: " + key);
                        }
                        String k = key.substring(0, eqIdx);
                        String v = key.substring(eqIdx + 1);
                        all_child.add(k);
                        current.value.add(new String[]{k, v});
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
