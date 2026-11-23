package me.karboom.java.filaments;

import cn.hutool.core.util.StrUtil;
import me.karboom.java.filaments.spec.FieldCondition;
import me.karboom.java.filaments.spec.LogicNode;
import me.karboom.java.filaments.spec.OrderSpec;
import me.karboom.java.filaments.spec.SelectSpec;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 查询规范构建器
 * 负责将 Map 参数解析为结构化 SelectSpec
 * 只负责语法解析，不涉及具体 ORM 实现
 */
public class SelectSpecBuilder {

    // ==================== 静态常量 ====================

    /** 合法别名（复用 SQL 标识符字符集，杜绝别名分隔符前后出现结构字符导致 key 解析歧义）；分隔符本身见 {@link FilamentsConfig#aliasSplitter} */
    static final Pattern ALIAS_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    // ==================== 成员变量 ====================

    /** 查询语法相关配置（分隔符、函数白名单） */
    public final FilamentsConfig config;

    public SelectSpecBuilder() {
        this(new FilamentsConfig());
    }

    public SelectSpecBuilder(FilamentsConfig config) {
        this.config = config != null ? config : new FilamentsConfig();
    }

    // ==================== 公开方法 ====================

    /**
     * 解析为 SelectSpec（用于调试/序列化）
     */
    public SelectSpec parse(Map<String, Object> query) {
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
                default -> throw new FilamentsError("Unexpected value: " + rt);
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
            case null -> { }
            default -> throw new FilamentsError("Unexpected value: " + od);
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

        // 别名表：别名 -> 完整条件 key（"别名::字段|函数链|操作符"）
        var aliasMap = buildAliasMap(filteredQuery);

        // 解析 lg 子树
        LogicNode lgTree = null;
        var lg = (String) query.get("lg");
        if (lg != null) {
            lgTree = parseLogic(lg);
        }

        // lg 实际引用到的真实条件键（已解析别名），用于与外部参数去重
        Set<String> lgKeys = lgTree == null ? Set.of() : new HashSet<>(collectReferencedKeys(lgTree, aliasMap));

        // 没有未被 lg 覆盖的外部条件时，直接使用 lgTree，避免多余的顶层 AND 包裹
        if (lgTree != null && filteredQuery.keySet().stream().allMatch(lgKeys::contains)) {
            parseLogicTreeConditions(lgTree, filteredQuery, aliasMap);
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
        for (String key : filteredQuery.keySet()) {
            if (!lgKeys.contains(key)) {
                topNode.getValue().add(key);
            }
        }
        topNode.getAllChild().addAll(filteredQuery.keySet());
        if (lgTree != null) {
            topNode.getAllChild().addAll(lgKeys);
        }

        // 将 lg 子树加入顶层节点
        if (lgTree != null) {
            topNode.getValue().add(lgTree);
            topNode.getChildren().add(lgTree);
        }

        parseLogicTreeConditions(topNode, filteredQuery, aliasMap);
        spec.setWhere(topNode);
    }

    /**
     * 收集所有带别名前缀的条件键，构建 别名 -> 完整键 映射。
     * 别名非法或重复直接拒绝，避免后续寻址歧义。
     */
    private Map<String, String> buildAliasMap(Map<String, Object> filteredQuery) {
        Map<String, String> aliasMap = new HashMap<>();
        for (String key : filteredQuery.keySet()) {
            int idx = key.indexOf(config.aliasSplitter);
            if (idx < 0) {
                continue;
            }
            var alias = key.substring(0, idx);
            var rest = key.substring(idx + config.aliasSplitter.length());
            if (!ALIAS_PATTERN.matcher(alias).matches()) {
                throw new FilamentsError("非法条件别名: " + key);
            }
            if (rest.isEmpty() || rest.contains(config.aliasSplitter)) {
                throw new FilamentsError("条件键中的别名只能出现一次且不能为空: " + key);
            }
            var prev = aliasMap.put(alias, key);
            if (prev != null) {
                throw new FilamentsError("重复的条件别名: " + alias);
            }
        }
        return aliasMap;
    }

    /** 把 lg 叶子 token（别名或裸键）解析为查询参数里的真实条件键 */
    private String resolveConditionKey(String token, Map<String, String> aliasMap) {
        return aliasMap.getOrDefault(token, token);
    }

    /** 递归收集逻辑树引用到的真实条件键（裸 token 与别名引用都算） */
    private Set<String> collectReferencedKeys(LogicNode node, Map<String, String> aliasMap) {
        Set<String> keys = new LinkedHashSet<>();
        for (Object item : node.getValue()) {
            if (item instanceof String s) {
                keys.add(resolveConditionKey(s, aliasMap));
            } else if (item instanceof LogicNode child) {
                keys.addAll(collectReferencedKeys(child, aliasMap));
            }
        }
        return keys;
    }

    private void parseLogicTreeConditions(LogicNode node, Map<String, Object> query,
                                          Map<String, String> aliasMap) {
        for (int i = 0; i < node.getValue().size(); i++) {
            Object item = node.getValue().get(i);
            if (item instanceof String) {
                String key = resolveConditionKey((String) item, aliasMap);
                if (!query.containsKey(key)) {
                    throw new FilamentsError("lg 表达式引用了未知的条件: " + item);
                }
                var pickedQuery = new HashMap<>(query);
                pickedQuery.keySet().retainAll(List.of(key));
                FieldCondition cond = buildFieldCondition(pickedQuery, key);
                node.getValue().set(i, cond);
            } else if (item instanceof LogicNode) {
                parseLogicTreeConditions((LogicNode) item, query, aliasMap);
            }
        }
    }

    /**
     * 分页解析：{@code p} 与 {@code pc} 任一键出现即进入分页，缺的那个取 config 默认值。
     * <p>只认 {@code pc} 会把「只传 {@code p}」当成没有分页意图：{@code p} 被丢弃、SQL 不带
     * limit/offset，调用方拿到整个表却看不出任何异常——静默返回全表比返回一页错数据更难查。
     * <p>两个键都不给时仍不分页：{@code get} 是内部全量取数入口，不该被这里的默认值悄悄截断；
     * 要"永远分页"的调用方走 {@code Filaments#getByPages}，它自己把两个键都补齐。
     */
    private void parsePagination(Map<String, Object> query, SelectSpec spec) {
        var hasPc = query.containsKey("pc");
        var hasPage = query.containsKey("p");
        if (!hasPc && !hasPage) {
            return;
        }

        var pc = hasPc ? positiveLong(query, "pc") : config.pageSize;
        spec.setLimit(pc);

        // 只给 pc 不写 offset：语义就是第 1 页，SQL 保持 `fetch next ? rows only`，
        // 多渲染一个 `offset 0` 只是噪声
        if (!hasPage) {
            return;
        }

        var page = positiveLong(query, "p");
        try {
            spec.setOffset(Math.multiplyExact(page - 1, pc));
        } catch (ArithmeticException e) {
            throw new FilamentsError("分页偏移溢出：(p-1)*pc 超出 long 范围，p=%s, pc=%s".formatted(page, pc), e);
        }
    }

    /**
     * 分页参数取整数：非数字、非正数都指到「哪个键、传了什么」。
     * <p>正数不是额外要求而是必需的：{@code pc=0} 或 {@code p=0} 会渲染成 {@code limit 0} /
     * {@code offset -20} 这类合法但语义相反的 SQL，报错点跑到 DB 侧，看不出是参数问题。
     * <p>用 Long 而非 Integer：offset = (p-1)*pc 在 int 域会溢出成负数再渲染进 SQL，
     * long 域溢出概率极低但仍可能发生，故 offset 计算用 {@link Math#multiplyExact} 兜底，
     * 溢出直接报错而不是把负数交给 DB；与 total 的 bigint 保持同一数值空间。
     */
    private Long positiveLong(Map<String, Object> query, String key) {
        var raw = String.valueOf(query.get(key));

        try {
            var num = Long.parseLong(raw);
            if (num <= 0) {
                throw new FilamentsError("分页参数 %s 必须是正整数，实际: %s".formatted(key, raw));
            }
            return num;
        } catch (NumberFormatException e) {
            throw new FilamentsError("分页参数 %s 必须是整数，实际: %s".formatted(key, raw), e);
        }
    }

    // ==================== 条件解析方法 ====================

    /**
     * 条件键 -> {@link FieldCondition}。
     *
     * <p>执行流程：剥别名前缀 → 按 {@code nameSplitter} 拆 key → 首段作字段名、
     * 末段命中操作符表则作操作符（否则按隐式 eq，整段留在函数链里）→ 中间段作函数链。
     *
     * <p>值原样收进 {@code values}，逗号不在这里拆：要不要拆由操作符在渲染期决定
     * （{@code Filaments#buildConditionFromFieldCondition}，那里才知道 SQL 要几个占位符）。
     * null 也在这里原样收下——它是"值不对"还是"根本不该有值"由操作符判定，拒绝点同在渲染期。
     */
    public FieldCondition buildFieldCondition(Map<String, Object> pickedQuery, String key) {
        var rawValue = pickedQuery.get(key);

        // 剥掉 "别名::" 前缀，别名只做寻址，不进入字段/函数链解析
        int aliasIdx = key.indexOf(config.aliasSplitter);
        if (aliasIdx >= 0) {
            key = key.substring(aliasIdx + config.aliasSplitter.length());
        }

        var leftParts = StrUtil.split(key, config.nameSplitter);
        var fieldName = leftParts.get(0);

        FieldCondition cond = new FieldCondition();
        cond.setField(fieldName);
        // 唯一元素就是键上绑的那个东西本身（集合也不展开），否则分不清「含逗号的串」和「装一个串的集合」
        cond.setValues(Arrays.asList(rawValue));

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
            // 空值判定：末段是操作符，值一律不参与 SQL
            case "isNull" -> cond.setOperator(FieldCondition.IS_NULL);
            case "isNotNull" -> cond.setOperator(FieldCondition.IS_NOT_NULL);
            default -> {
                cond.setOperator(FieldCondition.EQ);
                isOperator = false;
            }
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
     * {@code lg} 里 {@code ?N} 指向本层第 N 个子节点（N 由 {@link #parseLogic} 生成，但整段 str
     * 由用户输入拼成，所以 N 不一定真是数字）。
     * <p>非数字与越界都指到具体 token：否则 {@code (?}、{@code (?9)} 分别抛
     * NumberFormatException、IndexOutOfBoundsException，看不出是逻辑表达式写坏了。
     *
     * @param token     以 {@code ?} 开头的序号 token
     * @param childCount 本层已有的子节点个数
     * @return 可直接用于 {@code children.get} 的序号
     */
    private Integer logicChildIndex(String token, Integer childCount) {
        try {
            var index = Integer.parseInt(token.substring(1));
            if (index < 0 || index >= childCount) {
                throw new FilamentsError("lg 表达式引用的子节点序号越界: %s（当前层共 %s 个）".formatted(token, childCount));
            }
            return index;
        } catch (NumberFormatException e) {
            throw new FilamentsError("lg 表达式的子节点序号不是整数: %s".formatted(token), e);
        }
    }

    /**
     * 解析 lg 逻辑表达式为条件树，token 为查询 key 的引用（从 0 开始计算索引）。
     * <p>lg 只做参数键的组合逻辑：不支持内联值 {@code k=v}（值只能是字符串、逗号被当分隔符、
     * 同一条件出现两个值来源），出现即带原文报错；条件值一律走查询参数。
     * <p>括号必须严格配对且只允许一个顶层组：漏右括号时括号内容只积累在 str 里、
     * 永远进不了 value，外层会把这些条件静默降级为顶层 AND（! 的 OR 语义被吞）；
     * 多右括号会 pop 掉 root 后 peek 抛裸 EmptyStackException。两种都在这一步
     * 带位置信息拒绝，绝不带着被改写的语义往下走。
     * <p>顶层括号外的裸 token（如 {@code a}、{@code a,(b,c)}）与括号内同样进 value，
     * 多个顶层元素按 AND 组合，不会被静默丢弃。
     *
     * @param input lg 表达式字符串
     * @return 顶层 LogicNode
     */
    public LogicNode parseLogic(String input) {
        if (input.equals("!")) {
            input = "!()";
        }

        LogicNode root = new LogicNode();
        LogicNode current = root;
        Stack<LogicNode> stack = new Stack<>();
        stack.push(root);
        /** 与 stack 平行：每个未闭合 '(' 在 input 中的位置，报错时能指到具体括号 */
        Stack<Integer> openPositions = new Stack<>();
        openPositions.push(-1);

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
                openPositions.push(i);
                current = node;
            } else if (charAt == ')') {
                if (stack.size() == 1) {
                    throw new FilamentsError("lg 表达式右括号多余: %s（位置 %d）".formatted(input, i));
                }
                fillTokens(current, input);

                stack.pop();
                openPositions.pop();
                current = stack.peek();
            } else {
                if (charAt != '!') {
                    current.str += charAt;
                }
            }
        }

        if (stack.size() > 1) {
            throw new FilamentsError("lg 表达式缺少右括号: %s（未闭合的左括号位于位置 %d）"
                    .formatted(input, openPositions.peek()));
        }
        if (root.children.size() > 1) {
            // 只取第一个组会把其余括号组无声丢弃，同样属于静默改写语义
            throw new FilamentsError("lg 表达式存在多个顶层括号组: %s".formatted(input));
        }

        // 顶层括号外的裸 token 与括号内同样处理；不 flush 的话它们只积在 root.str，
        // root.value 恒空，条件被静默丢弃（条件全写在 lg 里时退化成无条件全表）
        fillTokens(root, input);

        // 整个表达式就是一个括号组时直接返回该组，省掉多余的外层包裹
        if (root.children.size() == 1 && root.value.size() == 1
                && root.value.get(0) == root.children.get(0)) {
            return root.children.get(0);
        }
        return root;
    }

    /**
     * 把节点累积的 {@code str} 按逗号切 token 并填进 value/allChild。
     * <p>{@code ?N} 指向子节点；其余为裸 token（条件键或别名引用），
     * 能否解析留到 parseLogicTreeConditions 查表时校验。
     * <p>lg 只表达参数键的组合逻辑：内联值（{@code k=v}）意味着同一条件有两个值来源
     * 且值只能是字符串（逗号被当分隔符、多值操作符不可用），直接拒绝而不是支持一半。
     */
    private void fillTokens(LogicNode node, String input) {
        Set<String> allChild = new HashSet<>();
        for (String key : node.str.split(",")) {
            if (key.isEmpty()) continue;
            if (key.startsWith("?")) {
                node.value.add(node.children.get(
                        logicChildIndex(key, node.children.size())));

                for (LogicNode child : node.children) {
                    allChild.addAll(child.allChild);
                }
            } else if (key.contains("=")) {
                throw new FilamentsError("lg 表达式不支持内联值（k=v），值请放查询参数: %s（token %s）"
                        .formatted(input, key));
            } else {
                allChild.add(key);
                node.value.add(key);
            }
        }
        node.allChild = new ArrayList<>(allChild);
    }

    public String buildHolder(Object[] value, String splitter) {
        return Stream.of(value)
                .map(_ -> "?")
                .collect(Collectors.joining(" " + splitter + " "));
    }

    /**
     * 逗号展开：String 按逗号拆成多个元素，List/Set 原样，标量包一层。
     *
     * <p>只服务「一个聚合函数对应多列」这类字段清单展开（{@code Map.of("count", "id,type")}）；
     * 条件值不经过这里——是否按逗号拆由操作符在渲染期决定，见 {@link #buildFieldCondition}。
     */
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
            String funcName = func.trim();
            if (funcName.contains("(")) {
                int openIdx = funcName.indexOf('(');
                int startIndex = openIdx + 1;
                int endIndex = funcName.indexOf(')');
                // 括号必须完整闭合且后面不带残渣：否则 substr(1,2、lower( 这类写法
                // 会走到下面的截断，把参数静默吞掉而不是报错
                if (endIndex < startIndex || !funcName.substring(endIndex + 1).isEmpty()) {
                    throw new FilamentsError("函数链括号不配对: %s".formatted(func));
                }
                if (startIndex < endIndex) {
                    paramList = List.of(funcName.substring(startIndex, endIndex).split(","));
                }
                funcName = funcName.substring(0, openIdx);
            }

            // 防注入：函数名必须精确命中白名单，括号参数仅允许数字字面量
            final String checkedName = funcName.trim();
            var whiteListed = config.sqlFuncList.stream()
                    .anyMatch(w -> w.equalsIgnoreCase(checkedName));
            if (!whiteListed) {
                throw new FilamentsError("函数不在白名单（FilamentsConfig.sqlFuncList）: " + func);
            }
            for (String param : paramList) {
                if (!param.trim().matches("\\d+")) {
                    throw new FilamentsError("函数参数仅允许数字字面量: " + func);
                }
            }

            String paramStr = paramList.isEmpty() ? "" : ", " + String.join(",", paramList);
            sqlField = checkedName + "(" + sqlField + paramStr + ")";
        }

        return sqlField;
    }
}
