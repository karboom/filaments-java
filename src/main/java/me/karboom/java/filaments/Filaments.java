package me.karboom.java.filaments;

import am.ik.yavi.builder.ValidatorBuilder;
import am.ik.yavi.core.CollectionValidator;
import am.ik.yavi.core.ConstraintCondition;
import am.ik.yavi.core.ConstraintPredicates;
import am.ik.yavi.core.Validatable;
import am.ik.yavi.core.Validator;
import am.ik.yavi.fn.Pair;
import am.ik.yavi.message.SimpleMessageFormatter;

import cn.hutool.core.util.StrUtil;

import tools.jackson.databind.*;
import lombok.SneakyThrows;
import me.karboom.java.filaments.spec.FieldCondition;
import me.karboom.java.filaments.spec.LogicNode;
import me.karboom.java.filaments.spec.OrderSpec;
import me.karboom.java.filaments.spec.SelectSpec;
import org.jooq.*;
import org.jooq.Record;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.jooq.impl.DSL.*;

/**
 * 说明
 */

public abstract class Filaments<T> {

    // region 成员变量（static -> private -> public）

    // 合法 SQL 标识符 / jsonb 路径段（防注入：拒绝引号、括号、空白等结构字符）
    static final Pattern IDENT_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");


    private final Class<T> dataClass;

    /**
     * 模型字段索引：归一化列名 -> 字段，构造期按当时的 config 一次性建好。
     */
    private final Map<String, java.lang.reflect.Field> modelFields;

    /**
     * 应按 JSON 存取的字段（归一化列名集合），构造期按声明类型分类：
     * 即 {@link #isJsonType} 判真的字段（POJO/List/Map/数组）。
     * 写侧（create/update）据此序列化，读侧 rt 投影据此补 JSONB 类型；
     * 声明为 Object 的字段在构造期直接拒绝——存成标量还是 JSON 说不清，必须用具体类型。
     */
    private final Set<String> jsonFields;

    /** 语法解析器，构造期随 config 绑定，之后不可变 */
    protected final SelectSpecBuilder specBuilder;

    public String table;

    /** 主键的模型字段名；默认为 "id"，不同主键经构造函数传入 */
    public final String pk;

    /**
     * 命名等配置。构造期定死，之后不可更换；不传则每个实例各持一份内置默认配置，
     * 自定义时把 {@code new FilamentsConfig()} 经构造函数传入（多实例共享一份就传同一对象）。
     */
    public final FilamentsConfig config;

    // endregion

    /**
     * 构造期划分 {@link #jsonFields} 的谓词：除 String/数字包装/时间/UUID/byte[]、
     * 枚举、{@code java.util/sql} 日期之外的类型（POJO、集合、Map、数组）按 JSON 字段对待。
     * 基本类型（int、long 等）不在普通值之列——模型请用封装类；Object 声明已在构造期拒绝。
     */
    static boolean isJsonType(Class<?> type) {
        if (type.isEnum() || type == java.util.Date.class || type == java.sql.Timestamp.class
                || type == java.sql.Time.class || type == java.sql.Date.class
                || type == org.jooq.JSON.class || type == org.jooq.JSONB.class) {
            // jOOQ JSON/JSONB 是可直接绑定的值，不算 JSON 字段——否则会被二次序列化变味
            return false;
        }
        return !Set.of(String.class, Boolean.class, Character.class, Byte.class,
                Short.class, Integer.class, Long.class, Float.class, Double.class,
                BigDecimal.class, BigInteger.class, byte[].class, UUID.class,
                java.time.LocalDate.class, java.time.LocalTime.class, java.time.LocalDateTime.class,
                java.time.OffsetDateTime.class, java.time.OffsetTime.class,
                java.time.ZonedDateTime.class, java.time.Instant.class).contains(type);
    }

    // region 基础
    public Filaments(String table) {
        this(table, "id", new FilamentsConfig());
    }

    /** 主键不叫 "id" 的表 */
    public Filaments(String table, String pk) {
        this(table, pk, new FilamentsConfig());
    }

    /**
     * 实例级配置入口（多库 / 遗留命名不同的表）
     */
    public Filaments(String table, FilamentsConfig config) {
        this(table, "id", config);
    }

    /** 全参入口：表名 + 主键模型字段名 + 配置 */
    public Filaments(String table, String pk, FilamentsConfig config) {
        this.table = table;
        this.pk = pk;
        this.config = config;

        // 构造期就绑好配置，子类直接用 specBuilder 字段也不会拿到 null
        this.specBuilder = new SelectSpecBuilder(config);

        ParameterizedType superClass = (ParameterizedType) getClass().getGenericSuperclass();

        this.dataClass = (Class<T>) superClass.getActualTypeArguments()[0];

        // 列名 -> 字段索引 + JSON 字段集：config 构造期定死，建一次即可
        var indexed = new HashMap<String, java.lang.reflect.Field>();
        var json = new HashSet<String>();
        for (var rf : this.dataClass.getDeclaredFields()) {
            if (Modifier.isStatic(rf.getModifiers())) {
                continue;
            }
            if (rf.getType() == Object.class) {
                throw new Error("模型字段 " + rf.getName() + " 声明为 Object：jsonb 字段请用具体类型（POJO/List/Map）");
            }
            var col = config.naming.apply(rf.getName());
            indexed.put(col, rf);
            if (isJsonType(rf.getType())) {
                json.add(col);
            }
        }
        this.modelFields = Map.copyOf(indexed);
        this.jsonFields = Set.copyOf(json);
    }

    /**
     * 按模型字段名读字段值（约束名用的是声明字段名，不走列名归一化）。
     */
    @SneakyThrows
    private Object modelValue(T data, String fieldName) {
        var rf = dataClass.getDeclaredField(fieldName);
        rf.setAccessible(true);
        return rf.get(data);
    }

    public T before (T data) {
        return data;
    };

    public T after (T data) {
        return data;
    }


    abstract public ValidatorBuilder<T> initValidator();


    private String fieldNameSafe(String fieldName) {
        return fieldName.replace("`", "");
    }

    /**
     * 过滤函数名：不在白名单内直接拒绝（此前返回 "" 会拼出坏 SQL）
     */
    private String funcNameSafe(String funcName) {
        for (String func : config.sqlFuncList) {
            if (funcName.toLowerCase().contains(func)) {
                return func;
            }
        }
        throw new Error("不支持的聚合函数: " + funcName);
    }

    /**
     * 校验字段标识符（支持点分 jsonb 路径），任何一段含非法字符直接拒绝
     */
    static List<String> identsSafe(String fieldName) {
        var segments = StrUtil.split(fieldName, '.');
        for (String seg : segments) {
            if (!IDENT_PATTERN.matcher(seg).matches()) {
                throw new Error("非法字段名: " + fieldName);
            }
        }
        return segments;
    }

    /**
     * 模型字段名 -> 列引用：列名进 jOOQ 的唯一合法通道（标识符，而非 plain SQL 拼接）。
     * 列名由 {@link FilamentsConfig#naming} 归一化：默认 {@code snake} 驼峰转下划线并统一小写——
     * hutool 的 toUnderlineCase 不降格连续大写（userID -> user_ID、USER -> USER），而 jOOQ 经
     * name() 加引号是精确匹配，与建表惯用的全小写列名不符会直接报 column does not exist；
     * 输入已是目标风格时幂等（create_time -> create_time）。
     */
    private Field<Object> columnRef(String fieldName) {
        return field(name(config.naming.apply(fieldName)));
    }

    /** 当前表引用 */
    private Table<Record> tableRef() {
        return table(name(this.table));
    }

    /**
     * 普通列表达式：白名单校验 + config.naming 归一化，以 name() 标识符引用（加引号，非 plain SQL 裸拼）。
     */
    private String columnExpr(String fieldName) {
        if (!IDENT_PATTERN.matcher(fieldName).matches()) {
            throw new Error("非法字段名: " + fieldName);
        }
        return name(config.naming.apply(fieldName)).toString();
    }

    /**
     * 点分 jsonb 路径表达式：逐段过标识符白名单（identsSafe），
     * 段 0 按列处理（复用 {@link #columnExpr}），其余段按 JSON 键归一化拼进 {@code #>> '{...}'}。
     * 「写进去的键」与「查询用的键」一致仍需 config.mapper 与 config.naming 配对。
     */
    private String jsonKeyExpr(String fieldName) {
        var segments = identsSafe(fieldName);
        var path = segments.subList(1, segments.size()).stream().map(config.naming).toList();
        return "%s #>> '{%s}'".formatted(
                columnExpr(segments.get(0)),
                String.join(",", path));
    }


    /**
     * 聚合列别名 = 函数名 + 各命名段首字母大写的拼接（{@code count + wechat_id -> countWechatId}）。
     * pages() 取 count 时走同一个 helper，保证两处不会漂移。
     * 不直接用 hutool toCamelCase 是因为它会把驼峰段整体降格（countDistinct_id -> countdistinctId）。
     */
    private String aggAlias(String funcName, String fieldName) {
        var sb = new StringBuilder(funcName);
        for (var seg : config.naming.apply(fieldNameSafe(fieldName)).split("[._]")) {
            if (seg.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(seg.charAt(0))).append(seg.substring(1));
        }
        return sb.toString();
    }

    // endregion




    // region 新增

    /**
     * 构建本行数据的校验器：约束名根字段（点分路径第一段）为 null 时跳过整条约束，
     * 规避 "a.b" 这类嵌套约束在父对象为 null 时取值 NPE 的问题。
     *
     * <p>只用 yavi 公共 API（build + forEach* + 公共构造器）重建 Validator：
     * 不反射触碰 ValidatorBuilder 内部字段（上游升级不碎），也不改动传入的 builder
     * （旧实现 removeIf 作用于原 builder，共享 builder 会被逐行永久删约束）。
     *
     * <p>取舍：builder 的 messageKeySeparator / MessageFormatter 无公共 getter，
     * 此处统一取 yavi 默认值（"."、SimpleMessageFormatter）。
     */
    public Validator<T> defaultValidator(ValidatorBuilder<T> builder, T data) {
        var base = builder.build();

        var kept = new ArrayList<ConstraintPredicates<T, ?>>();
        base.forEachPredicates(p -> {
            var rootObjKey = StrUtil.split(p.name(), ".").get(0);
            if (modelValue(data, rootObjKey) != null) {
                kept.add(p);
            }
        });

        var collectionValidators = new ArrayList<CollectionValidator<T, ?, ?>>();
        base.forEachCollectionValidator(collectionValidators::add);

        var conditionalValidators = new ArrayList<Pair<ConstraintCondition<T>, Validatable<T>>>();
        base.forEachConditionalValidator(conditionalValidators::add);

        // failFast 与旧调用链（.failFast(true).build()）保持一致
        return new Validator<>(".", kept, collectionValidators, conditionalValidators,
                SimpleMessageFormatter.getInstance(), true);
    }

    /**
     * 模型值 -> 可绑库的值，按构造期的 {@link #jsonFields} 判定，不看运行时值的来历：
     * JSON 字段必为 POJO/集合/Map，直接序列化；
     * 枚举按 {@code name()} 绑定（序列化成带引号的 "\"A\"" 会让 varchar(1) 直接超长）；
     * 其余标量（含日期、jOOQ JSON/JSONB——其列声明在 {@link #isJsonType} 中已划入非 JSON）原样绑定。
     */
    @SneakyThrows
    private Object handleJson(String col, Object value) {
        if (jsonFields.contains(col)) {
            return config.mapper.writeValueAsString(value);
        }
        return value instanceof Enum<?> e ? e.name() : value;
    }

    /**
     * 新增数据
     *
     */
    @SneakyThrows
    public Integer create(DSLContext db, List<T> data) {
        var objectFields = List.of(this.dataClass.getDeclaredFields());

        var dbFields = objectFields.stream()
                .map(r -> columnRef(r.getName()))
                .toList();

        var stat = db
                .insertInto(tableRef())
                .columns(dbFields)
                ;



        for (var i = 0; i < data.size(); i++) {
            // Todo 是否需要先copy一份对象，这种写法需要对象实现Cloneable接口？
            var row = data.get(i);

            // 自定义预处理
            this.before(row);

            // Todo 自定义模型处理函数
            var validator = defaultValidator(initValidator(), row);
            var result = validator.validate(row);
            if (!result.isValid()) {
                throw new Error(result.getFirst().message());
            }

            var values = new ArrayList<>();
            for (var field : objectFields) {
                field.setAccessible(true);
                var value = field.get(row);
                if (value == null) {
                    values.add(defaultValue());
                } else {
                    values.add(handleJson(config.naming.apply(field.getName()), value));
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
        DeleteQuery<Record> q = db.deleteQuery(tableRef());
        q.addConditions(trueCondition().and(columnRef(this.pk).in(ids)));
        q.execute();
    }

    public void deleteByIds(DSLContext db, String id) {
        this.deleteByIds(db, Collections.singletonList(id));
    }

    // endregion

    // region 修改
    @SneakyThrows
    public void updateByIds(DSLContext db, List<String> ids, T data) {
        // Todo 补充所有的name函数?
        UpdateQuery<Record> q = db.updateQuery(tableRef());
        var fields = this.dataClass.getDeclaredFields();

        for (var field : fields) {
            field.setAccessible(true);
            String name = field.getName();

            // 主键不参与更新，避免批量更新时 SET 主键导致冲突（两边都归一化后再比）
            if (config.naming.apply(name).equals(config.naming.apply(this.pk))) {
                continue;
            }

            var value = field.get(data);

            if (value != null) {
                q.addValue(columnRef(name), handleJson(config.naming.apply(name), value));
            }
        }
        q.addConditions(trueCondition().and(columnRef(this.pk).in(ids)));

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
            sub.addFrom(tableRef());
            q.addFrom(table(sub));
        } else {
            q.addFrom(tableRef());
        }
        return q;
    }

    /**
     * 全模型投影：rt 缺省时替代裸 SELECT *——列清单取自构造期反射的 modelFields
     * （排序保证语句稳定），JSON 字段与 rt 通道同样补 JSONB 类型。
     * 模型里声明了而库里没有的列会直接 SQL 报错，符合"模型即表"的响亮失败约定。
     */
    private Field<?>[] modelProjection() {
        return modelFields.keySet().stream()
                .sorted()
                .map(col -> jsonFields.contains(col) ? field(name(col), JSONB.class) : columnRef(col))
                .toArray(Field<?>[]::new);
    }

    private void buildSelectFieldsFromSpec(SelectQuery<Record> q, SelectSpec spec) {
        List<String> fields = spec.getSelectFields();
        if (fields != null && !fields.isEmpty()) {
            for (String part : fields) {
                var col = config.naming.apply(fieldNameSafe(part));
                // JSON 字段（构造期分类）补 JSONB 类型，统一取值形态；varchar 存 JSON 文本的列
                // 也只是 JSONB.valueOf 包装，无 DB cast 风险。
                // 时间列无需补类型：doQuery 走列级映射（jOOQ 转换器）。
                q.addSelect(jsonFields.contains(col) ? field(name(col), JSONB.class) : columnRef(col));
            }
        } else {
            // 不再发 SELECT *：jOOQ 动态查询的 getSelect() 对通配投影是空的，
            // doQuery 的列级映射拿不到列清单，只会产出空壳对象
            q.addSelect(modelProjection());
        }
    }

    private void buildOrderFromSpec(SelectQuery<Record> q, SelectSpec spec) {
        List<OrderSpec> orderBy = spec.getOrderBy();
        if (orderBy != null) {
            for (OrderSpec os : orderBy) {
                // 防注入：用户可控排序键必须先经标识符校验，再以表达式引用
                var sortKey = fieldNameSafe(os.getField());
                var sortField = field(sortKey.contains(".") ? jsonKeyExpr(sortKey) : columnExpr(sortKey));
                if (OrderSpec.DESC.equals(os.getDirection())) {
                    q.addOrderBy(sortField.desc());
                } else {
                    q.addOrderBy(sortField.asc());
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

        // 防注入：普通列走 name() 引用 + 列名归一化；jsonb 路径逐段过标识符白名单校验
        var cleaned = fieldNameSafe(fieldName);
        var sqlField = cleaned.contains(".") ? jsonKeyExpr(cleaned) : columnExpr(cleaned);

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
                // 值不进 SQL 文本：经 jsonb_path_exists 的 vars 绑定参数传入（jsonpath 里用 @ == $qN 引用）
                var preds = new ArrayList<String>();
                var vars = new ArrayList<String>();
                var binds = new Object[value.length];
                for (var i = 0; i < value.length; i++) {
                    preds.add("@ == $q" + i);
                    vars.add("'q%d', {%d}".formatted(i, i));
                    binds[i] = String.valueOf(value[i]);
                }
                var str = "jsonb_path_exists(%s, '$[*] ? (%s)', jsonb_build_object(%s))"
                        .formatted(sqlField, String.join(" || ", preds), String.join(", ", vars));
                yield condition(str, binds);
            }
            case FieldCondition.EQ -> condition(String.format("%s = ?", funcChainResult), value);
            default -> throw new IllegalStateException("Unknown operator: " + fc.getOperator());
        };
    }


    /**
     * 列级直接映射（无行级 JSON 往返），挂在 fetch(RecordMapper) 上单趟完成：
     * 逐行 Record -> T，不攒整批 Result<Record>。每列按能力阶梯取值：
     *   1. JSON 字段（{@link #jsonFields}）：文本（JSON/JSONB/String 三形态）经 Jackson readValue，
     *      已是结构形态的走 convertValue；
     *   2. 时间家族：jOOQ 转换器 record.get(i, type)；util/sql 日期在预计算段即响亮拒绝；
     *   3. 运行时值已可赋值：直取；
     *   4. 其余（数字加宽、字符串转枚举等）：Jackson convertValue 单值转换。
     * 模型里没有的列（聚合别名等）跳过；null 不写字段，保持模型默认值。
     */
    @SneakyThrows
    protected List<T> doQuery(SelectQuery<Record> q, Boolean lock) {
        q.setForUpdate(lock);

        // 列策略预计算：每查询一次把"这列怎么取值"编译成取值函数（而不是每行重跑分类逻辑），
        // 同时读投影声明，fetch 之前就能拒绝非法时间类型
        var mapper = config.mapper;
        var rowFields = q.getSelect().toArray(new Field<?>[0]);
        int n = rowFields.length;
        var targets = new java.lang.reflect.Field[n];
        @SuppressWarnings("unchecked")
        var values = (java.util.function.Function<Record, Object>[]) new java.util.function.Function[n];
        for (var i = 0; i < n; i++) {
            var mf = modelFields.get(rowFields[i].getName());
            targets[i] = mf;
            if (mf == null) {
                continue;
            }
            var type = mf.getType();
            var idx = i;
            if (jsonFields.contains(rowFields[i].getName())) {
                // 按 String.class 取值：jOOQ 内建转换器把 JSON/JSONB/varchar 统一拆成原始文本，
                // 不经过 org.jooq 自有 JSON 类型（拆包语义见 JsonStringProbeTest）
                values[idx] = r -> mapper.readValue(r.get(idx, String.class), type);
            } else if (java.time.temporal.Temporal.class.isAssignableFrom(type)) {
                values[idx] = r -> r.get(idx, type);
            } else {
                if (type == java.util.Date.class || type == java.sql.Timestamp.class
                        || type == java.sql.Time.class || type == java.sql.Date.class) {
                    // 刻意响亮失败：util/sql 日期承载不了 timestamptz 的 offset 语义，
                    // 任何折算路线都是按某个时区静默改值；模型请改用 java.time（见探针测试）
                    throw new Error("时间字段 " + rowFields[idx].getName() + " 声明为 " + type.getSimpleName()
                            + "：请改用 OffsetDateTime / Instant / LocalDateTime");
                }
                values[idx] = r -> {
                    var raw = r.get(idx);
                    return type.isInstance(raw) ? raw : mapper.convertValue(raw, type);
                };
            }
        }

        var ctor = dataClass.getDeclaredConstructor();
        ctor.setAccessible(true);

        
        // 映射挂在取数循环上：逐行 Record -> T，取值只剩查表
        var rows = q.fetch(new RecordMapper<Record, T>() {
            @SneakyThrows
            @Override
            public T map(Record record) {
                var obj = ctor.newInstance();
                for (var i = 0; i < n; i++) {
                    if (targets[i] == null || record.get(i) == null) {
                        continue;
                    }
                    targets[i].set(obj, values[i].apply(record));
                }
                return obj;
            }
        });

        // 处理after
        return rows.stream().map(this::after).toList();
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
    public PageResult<T> getByPages(DSLContext db, Map<String, Object> query) {
        // 不能影响外部传入的参数
        var copyQuery = new HashMap<>(query);
        copyQuery.putIfAbsent("pc", config.pageSize);
        copyQuery.putIfAbsent("p", config.page);

        PageResult<T> res = new PageResult<>();

        res.list = this.get(db, copyQuery);

        if (copyQuery.containsKey("pg")) {

            copyQuery.remove("rt");
            copyQuery.remove("od");
            // 计数不能带分页：LIMIT/OFFSET 会作用在聚合行上，p>1 时直接查不到那行 count
            copyQuery.remove("p");
            copyQuery.remove("pc");

            var count = this.aggregation(db, Map.of("count", this.pk), copyQuery, List.of(), null);

            // 与 aggregation 中的别名规则保持一致（同一个 helper，避免两处漂移）
            var countKey = aggAlias("count", this.pk);
            res.total = Integer.valueOf(count.getFirst().get(countKey).toString());
        }


        return res;
    }

    public List<T> getByIds(DSLContext db, List<String> ids, Boolean lock) {
        SelectQuery<Record> select = db.selectQuery();
        select.addSelect(modelProjection());
        select.addFrom(tableRef());
        select.addConditions(trueCondition().and(columnRef(this.pk).in(ids)));

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
        var base = buildAggregation(db, target, query, group, sub);
        return base.fetch().intoMaps();
    }

    /**
     * 聚合查询构建（独立出来便于无连接渲染验证）
     */
    protected SelectQuery<Record> buildAggregation(DSLContext db, Map<String, Object> target, Map<String, Object> query, List<String> group, SelectQuery sub) {
        var base = buildSelect(db, query, sub);
        base.getSelect().clear();

        for (var f: group) {
            // 防注入：分组字段做标识符校验后用 Name 引用；
            // 必须走 field(name(...)) 而非 field(String)，否则 intoMaps 键会带上引用引号
            var cleaned = fieldNameSafe(f);
            Field<?> groupField;
            if (cleaned.contains(".")) {
                // 表达式当分组列时 intoMaps 的键是整段 SQL，用最后一个路径段（json 键）当别名
                var segments = identsSafe(cleaned);
                groupField = field(jsonKeyExpr(cleaned)).as(config.naming.apply(segments.getLast()));
            } else {
                identsSafe(cleaned);
                groupField = columnRef(cleaned);
            }
            base.addGroupBy(groupField);
            base.addSelect(groupField);
        }

        for (var func: target.keySet()) {
            var value = specBuilder.buildValue(target.get(func));

            for (var f: value) {
                var fieldName = fieldNameSafe((String) f);
                var funcName = funcNameSafe(func);
                // 防注入：字段名逐段过标识符白名单校验，拒绝结构字符拼入 SQL
                identsSafe(fieldName);
                var sqlField = fieldName.contains(".") ? jsonKeyExpr(fieldName) : columnExpr(fieldName);

                var alias = aggAlias(funcName, fieldName);
                var fieldExpr = String.format("%s(%s)", funcName, sqlField);

                if (func.equals("countDistinct")) {
                    funcName = "count";
                    alias = aggAlias(func, fieldName);
                    fieldExpr = String.format("%s(distinct %s)", funcName, sqlField);
                }

                base.addSelect(field(fieldExpr).as(alias));
            }

        }

        return base;
    }
    // endregion
}
