package me.karboom.java.filaments;

import am.ik.yavi.builder.ValidatorBuilder;
import am.ik.yavi.core.ConstraintPredicates;

import cn.hutool.core.util.StrUtil;
import com.esotericsoftware.reflectasm.FieldAccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.SneakyThrows;
import me.karboom.java.filaments.spec.FieldCondition;
import me.karboom.java.filaments.spec.LogicNode;
import me.karboom.java.filaments.spec.OrderSpec;
import me.karboom.java.filaments.spec.SelectSpec;
import org.jooq.*;
import org.jooq.Record;

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
    protected final SelectSpecBuilder specBuilder = new SelectSpecBuilder();

    protected SelectQuery<Record> buildSelect(DSLContext db, Map<String, Object> query, SelectQuery<Record> sub) {
        SelectQuery<Record> q = db.selectQuery();
        q = this.buildSub(q, sub);

        List<Condition> rawConditions = new ArrayList<>();
        if (query.get("raw") instanceof Condition raw) {
            rawConditions.add(raw);
        }

        var spec = specBuilder.parse(query, null);

        buildSelectFieldsFromSpec(q, spec);
        q.addConditions(buildConditionFromSpec(spec, rawConditions));
        buildOrderFromSpec(q, spec);

        if (spec.getLimit() != null) {
            q.addLimit(spec.getLimit());
            if (spec.getOffset() != null) {
                q.addOffset(spec.getOffset());
            }
        }

        return q;
    }

    protected SelectQuery<Record> buildSub(SelectQuery<Record> q, SelectQuery<Record> sub) {
        if (sub != null) {
            sub.addFrom(table(name(this.table)));
            q.addFrom(table(sub));
        } else {
            q.addFrom(table(name(this.table)));
        }
        return q;
    }

    private void buildSelectFieldsFromSpec(SelectQuery<Record> q, SelectSpec spec) {
        List<String> fields = spec.getSelectFields();
        if (fields != null && !fields.isEmpty()) {
            if (fields.size() == 1) throw new RuntimeException("至少返回两个字段");
            for (String part : fields) {
                q.addSelect(field(name(StrUtil.toUnderlineCase(part))));
            }
        } else {
            q.addSelect();
        }
    }

    private void buildOrderFromSpec(SelectQuery<Record> q, SelectSpec spec) {
        List<OrderSpec> orderBy = spec.getOrderBy();
        if (orderBy != null) {
            for (OrderSpec os : orderBy) {
                if (OrderSpec.DESC.equals(os.getDirection())) {
                    q.addOrderBy(field(os.getField()).desc());
                } else {
                    q.addOrderBy(field(os.getField()).asc());
                }
            }
        }
    }

    protected Condition buildConditionFromSpec(SelectSpec spec, List<Condition> rawConditions) {
        Condition c = noCondition();
        LogicNode where = spec.getWhere();
        if (where != null && !where.getValue().isEmpty()) {
            c = buildConditionFromLogicNode(where);
        }
        for (Condition raw : rawConditions) {
            c = c.and(raw);
        }
        return c;
    }

    private Condition buildConditionFromLogicNode(LogicNode node) {
        Condition condition = noCondition();
        for (Object item : node.getValue()) {
            Condition subCond;
            if (item instanceof LogicNode childNode) {
                subCond = buildConditionFromLogicNode(childNode);
            } else if (item instanceof FieldCondition fc) {
                subCond = buildConditionFromFieldCondition(fc);
            } else {
                continue;
            }
            condition = LogicNode.AND.equals(node.getType()) ? condition.and(subCond) : condition.or(subCond);
        }
        return condition;
    }

    protected Condition buildConditionFromFieldCondition(FieldCondition fc) {
        var value = fc.getValues().toArray();
        var fieldName = fc.getField();

        var sqlField = name(fieldNameSafe(fieldName)).toString();
        if (fieldName.contains(".")) {
            var segments = StrUtil.split(fieldName, '.');
            sqlField = "%s #>> '{%s}'".formatted(segments.get(0), String.join(",", segments.subList(1, segments.size())));
        }

        String funcChainResult = specBuilder.buildFuncChain(sqlField, fc.getFuncChain());

        return switch (fc.getOperator()) {
            case FieldCondition.NE -> condition(String.format("%s <> ?", funcChainResult), value);
            case FieldCondition.GE -> condition(String.format("%s >= ?", funcChainResult), value);
            case FieldCondition.GT -> condition(String.format("%s > ?", funcChainResult), value);
            case FieldCondition.LE -> condition(String.format("%s <= ?", funcChainResult), value);
            case FieldCondition.LT -> condition(String.format("%s < ?", funcChainResult), value);
            case FieldCondition.IN -> condition(String.format("%s in (%s)", funcChainResult, specBuilder.buildHolder(value, ",")), value);
            case FieldCondition.NOT_IN -> condition(String.format("%s not in (%s)", funcChainResult, specBuilder.buildHolder(value, ",")), value);
            case FieldCondition.BETWEEN -> condition(String.format("%s between (%s)", funcChainResult, specBuilder.buildHolder(value, "and")), value);
            case FieldCondition.NOT_BETWEEN -> condition(String.format("%s not between (%s)", funcChainResult, specBuilder.buildHolder(value, "and")), value);
            case FieldCondition.LIKE -> condition(String.format("%s like %s", funcChainResult, specBuilder.buildHolder(value, ",")), value);
            case FieldCondition.NOT_LIKE -> condition(String.format("%s not like %s", funcChainResult, specBuilder.buildHolder(value, ",")), value);
            case FieldCondition.INTERSECT -> {
                var jsonCond = Stream.of(value).map(v -> "@ == \"%s\"".formatted(v)).collect(Collectors.joining(" || "));
                var str = "jsonb_path_exists(%s, '$[*] ? (%s)')".formatted(sqlField, jsonCond);
                yield condition(str, value);
            }
            case FieldCondition.EQ -> condition(String.format("%s = ?", funcChainResult), value);
            default -> throw new IllegalStateException("Unknown operator: " + fc.getOperator());
        };
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

    /**
     * 查询全部接口，一般用于内部查表
     */
    public List<T> get(DSLContext db, Map<String, Object> query) {
        SelectQuery<Record> q = this.buildSelect(db, query, null);
        return this.doQuery(q, false);
    }

    /**
     * 分页查询接口，一般用于返回给前端
     */
    public PageResult<T> pages(DSLContext db, Map<String, Object> query) {
        // 不能影响外部传入的参数
        var copyQuery = new HashMap<>(query);
        copyQuery.putIfAbsent("pc", Filaments.DEFAULT_QUERY_PC);
        copyQuery.putIfAbsent("p", Filaments.DEFAULT_QUERY_P);

        PageResult<T> res = new PageResult<T>();

        res.list = this.get(db, copyQuery);

        if (copyQuery.containsKey("pg")) {

            copyQuery.remove("rt");
            copyQuery.remove("od");

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
            var value = specBuilder.buildValue(target.get(func));

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
}
