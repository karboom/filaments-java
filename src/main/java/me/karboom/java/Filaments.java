package me.karboom.java;

import am.ik.yavi.builder.ValidatorBuilder;
import am.ik.yavi.core.ConstraintPredicates;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import com.esotericsoftware.reflectasm.FieldAccess;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.SneakyThrows;
import org.jooq.*;
import org.jooq.Record;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.jooq.impl.DSL.*;

/**
 * 说明
 */

public abstract class Filaments<T> {
    public static List<String> KEEP_FIELDS = List.of();
    public static List<String> SQL_FUNC_LIST = List.of("date", "substr", "count");
    public static String NAME_SPLITTER = "|";
    public static Number DEFAULT_QUERY_P = 1;
    public static Number DEFAULT_QUERY_PC = 20;

    // 这个mapper用来处理json嵌套对象的转换
    public static ObjectMapper mapper;
    static {
        mapper = new ObjectMapper();
        // Todo JSON 里面的null如何处理？
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
//        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        JavaTimeModule javaTimeModule = new JavaTimeModule();

        mapper.registerModule(javaTimeModule);
    }

    private final Class<T> dataClass;
    private final FieldAccess access;


    public String table;
    public String pk = "id";



    // region 基础
    public Filaments(String table) {
        this.table = table;

        ParameterizedType superClass = (ParameterizedType) getClass().getGenericSuperclass();

        this.dataClass = (Class<T>) superClass.getActualTypeArguments()[0];
        this.access =  FieldAccess.get(this.dataClass);




    }

    public T before (T data) {
        return data;
    };

    public T after (T data) {
        return data;
    }


    abstract public ValidatorBuilder<T> initValidator();


    /**
     * 过滤字段包裹字符串, TODO 除了下划线，其他字符全部删掉
     */
    private String fieldNameSafe(String fieldName, String wrap) {
        return fieldName.replaceAll(wrap, "");
    }
    private String fieldNameSafe(String fieldName) {
        return this.fieldNameSafe(fieldName, "`");
    }

    /**
     * 过滤函数名
     */
    private String funcNameSafe(String funcName) {
        for (String func : Filaments.SQL_FUNC_LIST) {
            if (funcName.toLowerCase().contains(func)) {
                return func;
            }
        }
        return "";
    }

    // endregion




    // region 新增
    @SneakyThrows
    public ValidatorBuilder<T> defaultValidator(ValidatorBuilder<T> builder, T data) {
        var newBuilder = new ValidatorBuilder<T>(builder);

        var list = ValidatorBuilder.class.getDeclaredField("predicatesList");
        list.setAccessible(true);


        var newList = ( List<ConstraintPredicates<T, ?>>) list.get(builder);

        newList.removeIf(p -> {
            // 解决嵌套约束条件问题
            var rootObjKey = StrUtil.split(p.name(), ".").get(0);
            return access.get(data, rootObjKey) == null;
        });

        list.set(newBuilder, newList);

        return newBuilder;
    }

    @SneakyThrows
    public Object handleJson(Object value) {
        if (value instanceof List || value instanceof Set || value instanceof Map || value.getClass().getName().startsWith(dataClass.getName()) ||
                value.getClass().getClassLoader() != null) {
            value = mapper.writeValueAsString(value);
        }
        return value;
    }

    /**
     * 新增数据
     *
     */
    public Integer create(DSLContext db, List<T> data) {
        var objectFields = List.of(this.dataClass.getDeclaredFields());

        // Todo 字段名增加引号
        var dbFields = objectFields.stream()
                .map(r -> field(StrUtil.toUnderlineCase(r.getName())))
                .toList();

        var stat = db
                .insertInto(table(name(this.table)))
                .columns(dbFields)
                ;



        for (var i = 0; i < data.size(); i++) {
            // Todo 是否需要先copy一份对象，这种写法需要对象实现Cloneable接口？
            var row = data.get(i);

            // 自定义预处理
            this.before(row);

            // Todo 自定义模型处理函数
            var validator = defaultValidator(initValidator(), row).failFast(true).build();
            var result = validator.validate(row);
            if (!result.isValid()) {
                throw new Error(result.getFirst().message());
            }

            var values = new ArrayList<>();
            for (var field : objectFields) {
                var value = access.get(row, field.getName());
                if (value == null) {
                    values.add(defaultValue());
                } else {
                    // 处理json
                    value = handleJson(value);

                    values.add(value);
                }
            }

            stat = stat.values(values);
        }

        return stat.execute();
    }

    public Integer create(DSLContext db, List<T> data, Consumer handler) {
        return 1;
    }

    public Integer create(DSLContext db, T data) { return this.create(db, List.of(data));};

    // endregion

    // region 删除
    public void deleteByIds(DSLContext db, List<String> ids) {
        DeleteQuery<Record> q = db.deleteQuery(table(this.table));
        q.addConditions(trueCondition().and(field("id").in(ids)));
        q.execute();
    }

    public void deleteByIds(DSLContext db, String id) {
        this.deleteByIds(db, Collections.singletonList(id));
    }

    // endregion

    // region 修改
    public void updateByIds(DSLContext db, List<String> ids, T data) {
        // Todo 补充所有的name函数?
        UpdateQuery<Record> q = db.updateQuery(table(name(this.table)));
        var fields = this.dataClass.getDeclaredFields();

        for (var field : fields) {
            field.setAccessible(true);
            String name = field.getName();
            var value = access.get(data, field.getName());


            // Todo 是否考虑直接把主键字段屏蔽掉
            if (value != null) {
                value = handleJson(value);

                q.addValue(field(name(StrUtil.toUnderlineCase(name))), value);
            }
        }
        q.addConditions(trueCondition().and(field(name("id")).in(ids)));

        q.execute();
    }
    public void updateByIds(DSLContext db, String id, T data) {
        updateByIds(db, List.of(id), data);
    }

    // endregion


    // region 查询共用
    protected SelectQuery<Record> buildSelect(DSLContext db, Map<String, Object> query, SelectQuery<Record> sub) {
        SelectQuery<Record> q = db.selectQuery();

        q = this.buildSub(q, sub);
        q = this.buildReturn(q, query);

        q.addConditions(this.buildCondition(query));
        q = this.buildOrder(q, query);



        return q;
    }
    protected SelectQuery<Record> buildSub(SelectQuery<Record> q, SelectQuery<Record> sub) {
        if (sub != null) {
            // Todo 这样写没问题？
            sub.addFrom(table(name(this.table)));
            q.addFrom(table(sub));
        } else {

            q.addFrom(table(name(this.table)));
        }
        return q;
    }

    protected SelectQuery<Record> buildReturn(SelectQuery<Record> q, Map<String, Object> query) {
        var rt = query.get("rt");

        var list = new ArrayList<String>();
        if (rt != null) {
            switch (rt) {
                case String s -> {
                    list.addAll(StrUtil.split(s, ","));
                }
                case List l -> {
                    list.addAll(l);
                }
                // Todo 更换报错类
                default -> throw new IllegalStateException("Unexpected value: " + rt);
            }

            // 如果仅仅返回一个字段，数据转换会报错，暂时限制这种方式
            if (list.size() == 1) throw new RuntimeException("至少返回两个字段");

            for (String part : list) {
                q.addSelect(field(name(StrUtil.toUnderlineCase(part))));
            }
        } else {
            q.addSelect();
        }


        return q;
    }

    protected SelectQuery<Record> buildOrder(SelectQuery<Record> q, Map<String, ?> query) {
        var od = query.get("od");

        var list = new ArrayList<String>();

        switch (od) {
            case String s -> {
                list.addAll(StrUtil.split(s, ","));
            }
            case List l -> {
                list.addAll(l);
            }
            case null -> {}
            // Todo 更换报错类
            default -> throw new IllegalStateException("Unexpected value: " + od);
        }



        for (String part : list) {
            part = StrUtil.toUnderlineCase(part);

            if (part.startsWith("-")) {
                q.addOrderBy(field(part.substring(1)).desc());
            } else {
                q.addOrderBy(field(part).asc());
            }
        }


        return q;
    }



    public LogicTreeNode parseLogic(String input) {
        if (input.equals("!")) {
            input = "!()";
        }

        LogicTreeNode root = new LogicTreeNode();
        LogicTreeNode current = root;
        Stack<LogicTreeNode> stack = new Stack<>();
        stack.push(root);

        for (int i = 0; i < input.length(); i++) {
            char charAt = input.charAt(i);

            if (charAt == '(') {
                char prev = i > 0 ? input.charAt(i - 1) : '\0';
                String type = prev == '!' ? "or" : "and";
                LogicTreeNode node = new LogicTreeNode();
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

                        for (LogicTreeNode child : current.children) {
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
            // 空数组类型的条件直接过滤？
            // Todo 兼容ObjectNode
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
                // Todo 数据类型问题
                // Todo 参数安全问题
            }

            String paramStr = paramList.isEmpty() ? "" : ", " + String.join(",", paramList);
            sqlField = funcNameSafe(func) + "(" + sqlField + paramStr + ")";
        }

        return sqlField;
    }

    public Condition buildField(Map<String, Object> pickedQuery, String whereType) {
        Condition condition = noCondition();

        for (String key : pickedQuery.keySet()) {
            if (key.equals("raw")) {
                condition = whereType.equals("and") ? condition.and((Condition) pickedQuery.get(key)) : condition.or((Condition) pickedQuery.get(key));
                continue;
            }

            var value = buildValue(pickedQuery.get(key));

            var leftParts = StrUtil.split(key, Filaments.NAME_SPLITTER);
            var funcList = new ArrayList<>(leftParts).subList(1, leftParts.size());
            var funcListShort = new ArrayList<>(leftParts).subList(1, leftParts.size() > 1 ? leftParts.size() -1 : 1);
            var fieldName = StrUtil.toUnderlineCase(leftParts.get(0));


            var sqlField = name(fieldNameSafe(fieldName)).toString();
            if (fieldName.contains(".")) {
                // Todo 区分pg和mysql，暂时只有pg的
                var segments = StrUtil.split(fieldName, '.');

                sqlField = "%s #>> '{%s}'".formatted(segments.get(0), String.join(",", segments.subList(1, segments.size()) ));
            }
            if (fieldName.contains("[")) {

            }


            Condition subCond = switch (leftParts.getLast()) {
                case "ne" -> condition(String.format("%s <> ?", buildFuncChain(sqlField, funcListShort)), value);
                case "ge" -> condition(String.format("%s >= ?", buildFuncChain(sqlField, funcListShort)), value);
                case "gt" -> condition(String.format("%s > ?", buildFuncChain(sqlField, funcListShort)), value);
                case "le" -> condition(String.format("%s <= ?", buildFuncChain(sqlField, funcListShort)), value);
                case "lt" -> condition(String.format("%s < ?", buildFuncChain(sqlField, funcListShort)), value);
                case "in" -> condition(String.format("%s in (%s)", buildFuncChain(sqlField, funcListShort), buildHolder(value, ",")), value);
                case "notIn" -> condition(String.format("%s not in (%s)", buildFuncChain(sqlField, funcListShort), buildHolder(value, ",")), value);
                case "between" -> condition(String.format("%s between (%s)", buildFuncChain(sqlField, funcListShort), buildHolder(value, "and")), value);
                case "notBetween" -> condition(String.format("%s not between (%s)", buildFuncChain(sqlField, funcListShort), buildHolder(value, "and")), value);
                case "like" -> condition(String.format("%s like %s", buildFuncChain(sqlField, funcListShort), buildHolder(value, ",")), value);
                case "notLike" -> condition(String.format("%s not like %s", buildFuncChain(sqlField, funcListShort), buildHolder(value, ",")), value);

                default -> condition(String.format("%s = ?", buildFuncChain(sqlField, funcList)), value);
            };

            condition = whereType.equals("and") ? condition.and(subCond) : condition.or(subCond);
        }

        return condition;
    }
    private Condition buildTree( Map<String, Object> query, LogicTreeNode node) {
        Condition condition = noCondition();

        for (var child: node.value) {
            Condition subCond;
            if (child instanceof LogicTreeNode) {
                subCond = buildTree(query, (LogicTreeNode) child);
            } else {
                var pickedQuery = new HashMap<>(query);
                pickedQuery.keySet().retainAll(List.of(child));
                subCond = buildField(pickedQuery, node.type);
            }

            condition = node.type.equals("and") ? condition.and(subCond) : condition.or(subCond);
        }


        return condition;
    }

    protected Condition buildCondition(Map<String, Object> query) {
        Iterator<String> it = query.keySet().iterator();

        var filteredQuery = new HashMap<>(query);
        filteredQuery.keySet().removeAll(List.of("p", "pc", "od", "rt", "gp", "pg", "lg", "raw"));

        var logicTree = new LogicTreeNode();
        var lg = (String) query.get("lg");
        if (lg != null) {
            logicTree = parseLogic(lg);
        }

        var logicTreeDefaultValue = new HashMap<>(filteredQuery);
        logicTreeDefaultValue.keySet().removeAll(logicTree.allChild);
        logicTree.value.addAll(logicTreeDefaultValue.keySet());

        var condition = buildTree(filteredQuery, logicTree);

        return condition;
    }


    @SneakyThrows
    protected List<T> doQuery(SelectQuery<Record> q, Boolean lock) {
        q.setForUpdate(lock);

        // Todo 这里如果是性能热点，需要选用另外的转换方式
        // 先转json再转对象
        var json = q.fetch().formatJSON(new JSONFormat()
                .header(false)
                .recordFormat(JSONFormat.RecordFormat.OBJECT));

        // 为了兼容PG数据库指定返回字段的时候，json类型无法被正确识别
        var arrayNode = mapper.readValue(json, ArrayNode.class);
        for (var obj : arrayNode) {
            var it = obj.fields();
            while (it.hasNext()) {
                var field = it.next();
                var value = field.getValue().asText();
                if (value.startsWith("{") || value.startsWith("[")) {
                    field.setValue(mapper.readValue(value.toString(), JsonNode.class));
                }
            }
        }

        List<T> res = mapper.convertValue(arrayNode, mapper.getTypeFactory().constructParametricType(List.class, this.dataClass));

        // 处理after
        return res.stream().map(this::after).toList();
    }
    // endregion

    // region 直接查询
    public List<T> get(DSLContext db, Map<String, Object> query) {
        SelectQuery<Record> q = this.buildSelect(db, query, null);

        if (query.containsKey("pc")) {
            var pc = Integer.parseInt(query.get("pc").toString());
            q.addLimit(pc);

            // 页码参数不会单独存在
            if (query.containsKey("p")) {
                var p = Integer.parseInt(query.get("p").toString());
                q.addOffset((p - 1) * pc);
            }
        }

        return this.doQuery(q, false);
    }

    public PageResult<T> pages(DSLContext db, Map<String, Object> query) {
        // 不能影响外部传入的参数
        var copyQuery = new HashMap<>(query);
        copyQuery.putIfAbsent("pc", Filaments.DEFAULT_QUERY_PC);
        copyQuery.putIfAbsent("p", Filaments.DEFAULT_QUERY_P);

        PageResult<T> res = new PageResult<T>();

        res.list = this.get(db, copyQuery);

        if (copyQuery.containsKey("pg")) {

            copyQuery.remove("rt");

            var count = this.aggregation(db, new HashMap<>(){{
                put("count", "id");
            }}, copyQuery, List.of(), null);

            res.total = Integer.valueOf(count.getFirst().get("countId").toString());
        }


        return res;
    }

    public List<T> getByIds(DSLContext db, List<String> ids, Boolean lock) {
        SelectQuery<Record> select = db.selectQuery();
        // TODO table 函数是否安全
        select.addFrom(table(name(this.table)));
        select.addConditions(trueCondition().and(field(name(this.pk)).in(ids)));

        return this.doQuery(select, lock);
    }

    public List<T> getByIds(DSLContext db, List<String> ids) {
        return getByIds(db, ids, false);
    }

    public T getByIds(DSLContext db, String id, Boolean lock) {
        List<T> result = getByIds(db, List.of(id), lock);

        return result.isEmpty() ? null : result.get(0);
    }

    public T getByIds(DSLContext db, String id) {
        return getByIds(db, id, false);
    }

    // endregion

    // region 聚合

    /**
     * 通用聚合查询函数
     * @param db
     * @param target {聚合函数: [字段]}
     * @param query  筛选条件
     * @param group  分组条件
     * @param sub
     * @return
     */
    public List<Map<String, Object>> aggregation (DSLContext db, Map<String, Object> target, Map<String, Object> query, List<String> group, SelectQuery sub) {
        var base = buildSelect(db, query, sub);
        base.getSelect().clear();

        for (var f: group) {
            base.addGroupBy(field(f));
            base.addSelect(field(f));
        }

        for (var func: target.keySet()) {
            var value = buildValue(target.get(func));

            for (var f: value) {
                var fieldName = fieldNameSafe((String) f);
                var funcName = funcNameSafe(func);

                var alias = StrUtil.toCamelCase(String.format("%s_%s", funcName, fieldName));
                var fieldExpr = String.format("%s(%s)", funcName, fieldName);

                if (func.equals("countDistinct")) {
                    funcName = "count";
                    alias = String.format("%s%s", func, StrUtil.upperFirst(fieldName));
                    fieldExpr = String.format("%s(distinct %s)", funcName, fieldName);
                }

                base.addSelect(field(fieldExpr).as(alias));
            }

        }

        return base.fetch().intoMaps();
    }
    // endregion



    public static class PageResult<T> {
        public Integer total;
        public List<T> list;
    }


    public static class Error extends RuntimeException {
        public Error(String message) {
            super(message);
        }
    }

    private static class LogicTreeNode {
        public String type = "and";
        public List<Object> value = new ArrayList<>();
        public String str = "";
        public List<String> allChild = new ArrayList<>();
        public List<LogicTreeNode> children = new ArrayList<>();
    }
}
