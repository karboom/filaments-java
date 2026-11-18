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
import java.util.regex.Pattern;

import static org.jooq.impl.DSL.*;

/**
 * 说明
 */

public abstract class Filaments<T> {

    // region 成员变量（static -> private -> public）

    // 合法 SQL 标识符 / jsonb 路径段（防注入：拒绝引号、括号、空白等结构字符）
    static final Pattern IDENT_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** jsonb 路径里的数组下标段（纯数字），只在段 0 之后合法 */
    static final Pattern INDEX_PATTERN = Pattern.compile("\\d+");

    /** 用户写的方括号下标语法：data[0] -> data.0（归一化后才进校验与渲染） */
    static final Pattern ARRAY_INDEX = Pattern.compile("\\[(\\d+)]");


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

        // 模型类型在构造期就定死：raw 写法（extends Filaments）在这里拒，
        // 不留到第一次用 dataClass 才炸——那时报错点和原因已经隔着一整个查询。
        this.dataClass = resolveModelClass();

        // 列名 -> 字段索引 + JSON 字段集：config 构造期定死，建一次即可
        var indexed = new HashMap<String, java.lang.reflect.Field>();
        var json = new HashSet<String>();
        for (var rf : this.dataClass.getDeclaredFields()) {
            if (Modifier.isStatic(rf.getModifiers())) {
                continue;
            }
            if (rf.getType() == Object.class) {
                throw new FilamentsError("模型字段 " + rf.getName() + " 声明为 Object：jsonb 字段请用具体类型（POJO/List/Map）");
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
     * 沿父类链向上找 {@code Filaments<...>}，取它的实参作模型类型。链上的中间层只允许非泛型具体类。
     * <p>泛型中间类（{@code BaseF<T> extends Filaments<T>}）明确不支持：它的类型实参和 Filaments
     * 的形参之间没有可靠对应（位置可换、可以不转发，如 {@code Base2<A, B> extends Filaments<B>}），
     * 猜错就是拿错模型类静默读写，比构造期报错难查得多。非泛型中间类（具体子类再派生、匿名子类）
     * 模型类型仍然唯一确定，允许穿过。
     * <p>执行流程：取 {@code getGenericSuperclass()}，剥出 raw 类型——是 Filaments 就校验实参为具体类
     * 并返回；是带类型形参的其他类直接拒；否则把 raw 当作下一个 c 继续上溯。
     *
     * @return 全链路上唯一那处 {@code Filaments<Model>} 的模型类
     */
    @SuppressWarnings("unchecked")
    private Class<T> resolveModelClass() {
        // 显式声明为 Class<?>：getClass() 的静态类型是 Class<? extends Filaments<T>>，上溯时要往 c 里赋任意祖先类
        var c = (Class<?>) getClass();
        while (true) {
            var sup = c.getGenericSuperclass();
            if (sup == null) {
                throw new FilamentsError("%s 的父类链上没有 Filaments<YourModel>".formatted(getClass().getName()));
            }
            var raw = sup instanceof ParameterizedType pt ? (Class<?>) pt.getRawType() : (Class<?>) sup;
            if (raw == Filaments.class) {
                if (!(sup instanceof ParameterizedType superClass)) {
                    throw new FilamentsError("%s 必须以带模型类型的方式继承：extends Filaments<YourModel>".formatted(
                            getClass().getName()));
                }
                var typeArg = superClass.getActualTypeArguments()[0];
                if (!(typeArg instanceof Class<?> modelClass)) {
                    throw new FilamentsError("Filaments 的类型实参必须是具体模型类：%s 实际是 %s".formatted(
                            c.getName(), typeArg));
                }
                return (Class<T>) modelClass;
            }
            if (raw.getTypeParameters().length > 0) {
                throw new FilamentsError("不支持泛型中间类：%s 继承了 %s；中间类请写成非泛型具体类，模型类型只写在 extends Filaments<YourModel> 一处".formatted(
                        c.getName(), sup));
            }
            c = raw;
        }
    }

    /**
     * 按模型字段名读字段值（约束名用的是声明字段名，不走列名归一化）。
     * <p>取不到字段说明 {@code initValidator} 里的约束名和模型声明对不上（改过字段名、写错层级），
     * 这里指到模型类和字段名：这个调用发生在 yavi 遍历谓词的过程中，
     * 裸 NoSuchFieldException 冒出去看不出是校验配置错了。
     */
    @SneakyThrows
    private Object modelValue(T data, String fieldName) {
        try {
            var rf = dataClass.getDeclaredField(fieldName);
            rf.setAccessible(true);
            return rf.get(data);
        } catch (NoSuchFieldException e) {
            throw new FilamentsError("模型 %s 没有字段 %s：initValidator 的约束名与模型声明不一致".formatted(
                    dataClass.getSimpleName(), fieldName), e);
        }
    }

    /**
     * 写前钩子，create/update 均调用。
     * 基类以返回值为准：实现可以原地修改，也可以返回新对象。
     */
    public T before (T data) {
        return data;
    }

    public T after (T data) {
        return data;
    }


    public abstract ValidatorBuilder<T> initValidator();


    /**
     * 用户可控字段名的唯一入口：剥反引号 + 把方括号下标展开成点分段
     * （{@code data[0].type -> data.0.type}），渲染层只认点分这一种形态。
     * 只替换纯数字下标；{@code data[a]}、{@code data[0;drop table]} 之类不匹配即原样保留，
     * 残留的方括号/结构字符随后由 {@link #identsSafe} 拒绝。
     */
    private String fieldNameSafe(String fieldName) {
        return ARRAY_INDEX.matcher(fieldName.replace("`", "")).replaceAll(".$1");
    }

    /**
     * 过滤聚合函数名（{@code countDistinct} 这类语法糖不参与，由调用方先行拦下）。
     * 校验标准与 {@code SelectSpecBuilder.buildFuncChain} 对齐：trim 后忽略大小写精确命中
     * {@link FilamentsConfig#sqlFuncList}，命中不了直接拒绝。
     * 不用 contains 模糊匹配——旧写法会让 "counter" 静默降级成 count、"minmax" 命中白名单首个包含项。
     *
     * @param funcName 用户传入的聚合名
     * @return 白名单中的规范函数名
     */
    private String funcNameSafe(String funcName) {
        var name = funcName.trim();
        return config.sqlFuncList.stream()
                .filter(f -> f.equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new FilamentsError("不支持的聚合函数: " + funcName));
    }

    /**
     * 校验字段标识符（支持点分 jsonb 路径），任何一段含非法字符直接拒绝。
     * 段 0 是列名，只认标识符；其后各段允许 JSON 对象键（标识符）或数组下标（纯数字）。
     */
    static List<String> identsSafe(String fieldName) {
        var segments = StrUtil.split(fieldName, '.');
        if (!IDENT_PATTERN.matcher(segments.get(0)).matches()) {
            throw new FilamentsError("非法字段名: " + fieldName);
        }
        segments.subList(1, segments.size()).stream()
                .filter(seg -> !IDENT_PATTERN.matcher(seg).matches()
                        && !INDEX_PATTERN.matcher(seg).matches())
                .findFirst()
                .ifPresent(_ -> {
                    throw new FilamentsError("非法字段名: " + fieldName);
                });
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
            throw new FilamentsError("非法字段名: " + fieldName);
        }
        return name(config.naming.apply(fieldName)).toString();
    }

    /**
     * 点分 jsonb 路径表达式：逐段过标识符白名单（identsSafe），
     * 段 0 按列处理（复用 {@link #columnExpr}），其余段按 JSON 键归一化拼进 {@code #>> '{...}'}；
     * 数组下标段原样进路径数组（PG 用整型键取元素），对数字跑 naming 依赖 hutool 行为，不做这个假设。
     * 「写进去的键」与「查询用的键」一致仍需 config.mapper 与 config.naming 配对。
     */
    private String jsonKeyExpr(String fieldName) {
        var segments = identsSafe(fieldName);
        var path = segments.subList(1, segments.size()).stream()
                .map(seg -> INDEX_PATTERN.matcher(seg).matches() ? seg : config.naming.apply(seg))
                .toList();
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
     * 新增数据（批量），返回受影响行数。
     *
     * <p>入参问题在拼 SQL 之前全部抛出 {@link Error}：空列表与 null 列表（不抛的话只声明 columns
     * 没有 values 段，jOOQ 渲染成 {@code insert into t default values}，那是合法语句，会真写出
     * 一行全默认值数据）、列表内元素为 null（异常带下标）。
     * <p>校验逐行做但只在最后 execute 一次，因此任一行不过就不会有部分写入。
     */
    @SneakyThrows
    public Integer create(DSLContext db, List<T> data) {
        if (data == null || data.isEmpty()) {
            throw new FilamentsError("新增数据为空");
        }

        // 复用构造期索引而非重新 getDeclaredFields()：后者会把 static 字段带进列清单
        // （静态常量值被写库，或渲染出不存在的列）；modelFields 建索引时已跳过 static
        var objectFields = List.copyOf(this.modelFields.values());

        var dbFields = objectFields.stream()
                .map(r -> columnRef(r.getName()))
                .toList();

        var stat = db
                .insertInto(tableRef())
                .columns(dbFields);

        for (var i = 0; i < data.size(); i++) {
            // Todo 是否需要先copy一份对象，这种写法需要对象实现Cloneable接口？
            // 元素为 null 先退出：否则 before 钩子拿到 null，或一路走到反射取值处抛 NPE，都定位不到是第几条
            var item = data.get(i);
            if (item == null) {
                throw new FilamentsError("新增数据第 %s 条为 null".formatted(i));
            }

            // 自定义预处理（以钩子返回值为准，支持返回新对象）
            var row = this.before(item);

            // Todo 自定义模型处理函数
            var validator = defaultValidator(initValidator(), row);
            var result = validator.validate(row);
            if (!result.isValid()) {
                throw new FilamentsError(result.getFirst().message());
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

    public Integer create(DSLContext db, T data) {
        return this.create(db, List.of(data));
    }

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
        // 写前处理与 create 对称：钩子返回值为准；defaultValidator 跳过 null 根字段，
        // 只校验本次实际写入的非 null 字段，适配部分更新语义
        data = this.before(data);
        var validator = defaultValidator(initValidator(), data);
        var result = validator.validate(data);
        if (!result.isValid()) {
            throw new FilamentsError(result.getFirst().message());
        }

        UpdateQuery<Record> q = db.updateQuery(tableRef());
        // 同 create：走构造期索引，static 字段建索引时已过滤，不会进 SET 子句
        var fields = this.modelFields.values();

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

    /**
     * 行查询：基座（FROM + WHERE）+ 投影 + 排序 + 分页。
     */
    protected SelectQuery<Record> buildSelect(DSLContext db, Map<String, Object> query, SelectQuery<Record> sub) {
        var spec = specBuilder.parse(query);
        var q = buildBase(db, query, spec, sub);

        buildSelectFieldsFromSpec(q, spec);
        buildOrderFromSpec(q, spec);

        if (spec.getLimit() != null) {
            q.addLimit(spec.getLimit());
            if (spec.getOffset() != null) {
                q.addOffset(spec.getOffset());
            }
        }

        return q;
    }

    /**
     * 查询基座：FROM + WHERE，只到"取哪些行"为止。
     * 行查询在此之上叠投影/排序/分页，聚合在此之上叠 group by/聚合列——
     * 两者的结果形态语义不同，故共用基座而不是共用完整行查询。
     * spec 由调用方解析后传入，避免同一份 query 解析两次；raw 原生条件不走 spec，在这里直接取。
     */
    protected SelectQuery<Record> buildBase(DSLContext db, Map<String, Object> query, SelectSpec spec, SelectQuery<Record> sub) {
        SelectQuery<Record> q = db.selectQuery();
        q = this.buildSub(q, sub);

        // raw 供内部/子类带原生 Condition 进来：只有拿得准来历的代码才构造得出 Condition 实例。
        // 其余形态（如写成字符串 SQL 片段）必须拒绝而不是丢弃——丢弃就是 WHERE 少一个条件，
        // 结果集静默变大，调用方看不出自己查错了。
        var raw = query.get("raw");
        List<Condition> rawConditions = switch (raw) {
            case null -> List.of();
            case Condition condition -> List.of(condition);
            default -> throw new FilamentsError("raw 只接受 jOOQ Condition 实例，实际 %s: %s".formatted(
                    raw.getClass().getName(), raw));
        };
        q.addConditions(buildConditionFromSpec(spec, rawConditions));

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
     * rt 通道：指定列显式投影。
     * JSON 字段（构造期分类）补 JSONB 类型统一取值形态，varchar 存 JSON 文本的列也只是
     * JSONB.valueOf 包装，无 DB cast 风险；时间列不补类型，doQuery 走列级映射（jOOQ 转换器）。
     * 未指定 rt 时发 SELECT *，列清单由 doQuery 从结果集元数据取（见 doQuery）。
     */
    private void buildSelectFieldsFromSpec(SelectQuery<Record> q, SelectSpec spec) {
        List<String> fields = spec.getSelectFields();
        if (fields != null && !fields.isEmpty()) {
            for (String part : fields) {
                var col = config.naming.apply(fieldNameSafe(part));
                q.addSelect(jsonFields.contains(col) ? field(name(col), JSONB.class) : columnRef(col));
            }
        } else {
            q.addSelect(asterisk());
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

    /**
     * 多值形态：解析期把「键上绑的那个东西」原样收成一个元素，这里按它的真实类型摊开——
     * 单个 String 是 DSL 简写，按逗号拆（{@code in=1,2}）；{@code Collection} 逐元素取值，
     * 元素内部的逗号不再被拆；其余（标量、手工构造的多元素）直接作绑定序列。
     * 不校验个数——合不合法由取值的操作符决定。
     */
    private Object[] manyValues(FieldCondition fc) {
        var values = fc.getValues();
        if (values.size() == 1) {
            switch (values.getFirst()) {
                case String s -> {
                    return StrUtil.split(s, ",").toArray();
                }
                case Collection<?> c -> {
                    return c.toArray();
                }
                case null, default -> {
                }
            }
        }
        return values.toArray();
    }

    /**
     * 单值形态：键上绑的就是一个值——整串，含逗号的 like 模式 / JSON 文本 / 名字因此无需任何转义
     * （只有多值操作符才认逗号分隔简写，见 {@link #manyValues}）。集合里套一个值也认。
     *
     * <p>多于一个值直接抛错——jOOQ 对多余绑定值是静默丢弃的（只留第一个），
     * 不抛就是查出错数据而不是报错。
     */
    private Object singleValue(FieldCondition fc) {
        var values = fc.getValues();
        var candidates = values.size() == 1 && values.getFirst() instanceof Collection<?> c
                ? c.toArray()
                : values.toArray();
        if (candidates.length != 1) {
            throw new FilamentsError("操作符 %s 只接受 1 个值，实际 %d 个: %s".formatted(
                    fc.getOperator(), candidates.length, Arrays.toString(candidates)));
        }
        return candidates[0];
    }

    /** 区间形态：必须恰好两个值（between 的两端），多给少给都抛错而不是让 DB 报错或静默截断 */
    private Object[] rangeValues(FieldCondition fc) {
        var values = manyValues(fc);
        if (values.length != 2) {
            throw new FilamentsError("操作符 %s 需要 2 个值，实际 %d 个: %s".formatted(
                    fc.getOperator(), values.length, Arrays.toString(values)));
        }
        return values;
    }

    /**
     * FieldCondition -> jOOQ Condition。
     *
     * <p>值的形态在这里定型：几个占位符就取几个值，与解析期无关，所以手工构造的
     * FieldCondition（子类扩展 / {@code lg} 子树里塞的条件）也走同一套规则。
     */
    protected Condition buildConditionFromFieldCondition(FieldCondition fc) {
        var fieldName = fc.getField();

        // 防注入：普通列走 name() 引用 + 列名归一化；jsonb 路径逐段过标识符白名单校验
        var cleaned = fieldNameSafe(fieldName);
        var sqlField = cleaned.contains(".") ? jsonKeyExpr(cleaned) : columnExpr(cleaned);

        String funcChainResult = specBuilder.buildFuncChain(sqlField, fc.getFuncChain());

        return switch (fc.getOperator()) {
            case FieldCondition.EQ -> condition("%s = ?".formatted(funcChainResult), singleValue(fc));
            case FieldCondition.NE -> condition("%s <> ?".formatted(funcChainResult), singleValue(fc));
            case FieldCondition.GE -> condition("%s >= ?".formatted(funcChainResult), singleValue(fc));
            case FieldCondition.GT -> condition("%s > ?".formatted(funcChainResult), singleValue(fc));
            case FieldCondition.LE -> condition("%s <= ?".formatted(funcChainResult), singleValue(fc));
            case FieldCondition.LT -> condition("%s < ?".formatted(funcChainResult), singleValue(fc));
            // like 只有一个 pattern：多模式请用别名 + lg 组合（`l1::name|like=a%`、`l2::name|like=%b`）
            case FieldCondition.LIKE -> condition("%s like ?".formatted(funcChainResult), singleValue(fc));
            case FieldCondition.NOT_LIKE -> condition("%s not like ?".formatted(funcChainResult), singleValue(fc));
            case FieldCondition.IN -> {
                var many = manyValues(fc);
                yield condition("%s in (%s)".formatted(funcChainResult, specBuilder.buildHolder(many, ",")), many);
            }
            case FieldCondition.NOT_IN -> {
                var many = manyValues(fc);
                yield condition("%s not in (%s)".formatted(funcChainResult, specBuilder.buildHolder(many, ",")), many);
            }
            // 两端不加括号：`between (? and ?)` 在 PG 是语法错误
            case FieldCondition.BETWEEN -> condition("%s between ? and ?".formatted(funcChainResult), rangeValues(fc));
            case FieldCondition.NOT_BETWEEN -> condition("%s not between ? and ?".formatted(funcChainResult), rangeValues(fc));
            // 空值判定不带绑定参数：键上的值（=1 / 留空 / 任意内容）一律忽略，只渲染谓词本身。
            // 取反不需要专门语法：lg 里 !(name|isNull) 即 NOT (... is null)
            case FieldCondition.IS_NULL -> condition("%s is null".formatted(funcChainResult));
            case FieldCondition.IS_NOT_NULL -> condition("%s is not null".formatted(funcChainResult));
            case FieldCondition.INTERSECT -> {
                // 值不进 SQL 文本：经 jsonb_path_exists 的 vars 绑定参数传入（jsonpath 里用 @ == $qN 引用）
                var value = manyValues(fc);
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
            default -> throw new FilamentsError("Unknown operator: " + fc.getOperator());
        };
    }


    /**
     * 列级直接映射（无行级 JSON 往返），挂在 fetch(RecordMapper) 上单趟完成：
     * 逐行 Record -> T，不攒整批 {@code Result<Record>}。
     * 列清单取首行的结果集元数据（{@code record.fields()}），不取 {@code q.getSelect()}——
     * 后者只登记显式 Field，默认投影 {@code SELECT *} 在那里是空的，据此建策略会得到零列空壳对象。
     * 每列按能力阶梯取值：
     *   1. JSON 字段（{@link #jsonFields}）：文本（JSON/JSONB/String 三形态）经 Jackson readValue，
     *      已是结构形态的走 convertValue；
     *   2. 时间家族：jOOQ 转换器 record.get(i, type)；util/sql 日期在建策略时即响亮拒绝，
     *      因此空结果集（没有行可建策略）不做这项检查；
     *   3. 运行时值已可赋值：直取；
     *   4. 其余（数字加宽、字符串转枚举等）：Jackson convertValue 单值转换。
     * 模型里没有的列（聚合别名等）跳过；null 不写字段，保持模型默认值。
     * 默认投影是 SELECT *，因此模型声明了而库里没有的列不会报错，只是永远取不到值。
     * 取值失败（脏 jsonb、类型不匹配）包装为 {@link FilamentsError}，message 带行号/列名/目标类型，
     * 底层原因保留在 cause 里。
     */
    @SneakyThrows
    protected List<T> doQuery(SelectQuery<Record> q, Boolean lock) {
        q.setForUpdate(lock);

        var mapper = config.mapper;
        var ctor = dataClass.getDeclaredConstructor();
        ctor.setAccessible(true);

        // 映射挂在取数循环上：逐行 Record -> T，取值只剩查表
        var rows = q.fetch(new RecordMapper<Record, T>() {
            /** 列清单：取首行的结果集元数据，不取 q.getSelect()——后者只登记显式 Field，通配投影在那里是空的 */
            Field<?>[] rowFields;
            java.lang.reflect.Field[] targets;
            @SuppressWarnings("unchecked")
            java.util.function.Function<Record, Object>[] values = new java.util.function.Function[0];
            /** 行号（1 基）：取值失败时才知道是哪一行，逐行推进 */
            int row = 0;

            /**
             * 列策略建表：每查询一趟把"这列怎么取值"编译成取值函数，不在每行重跑分类逻辑。
             * 非法时间类型在这里拒绝——策略随首行建立，空结果集不会走到这一步。
             */
            private void initStrategies(Field<?>[] fields) {
                this.rowFields = fields;
                this.targets = new java.lang.reflect.Field[fields.length];
                @SuppressWarnings("unchecked")
                var funcs = (java.util.function.Function<Record, Object>[]) new java.util.function.Function[fields.length];
                this.values = funcs;

                for (var i = 0; i < fields.length; i++) {
                    var mf = modelFields.get(fields[i].getName());
                    this.targets[i] = mf;
                    if (mf == null) {
                        continue;
                    }
                    var type = mf.getType();
                    var idx = i;
                    if (jsonFields.contains(fields[i].getName())) {
                        // 按 String.class 取值：jOOQ 内建转换器把 JSON/JSONB/varchar 统一拆成原始文本，
                        // 不经过 org.jooq 自有 JSON 类型，再由 Jackson 按模型类型反序列化
                        funcs[idx] = r -> mapper.readValue(r.get(idx, String.class), type);
                    } else if (java.time.temporal.Temporal.class.isAssignableFrom(type)) {
                        funcs[idx] = r -> r.get(idx, type);
                    } else {
                        if (type == java.util.Date.class || type == java.sql.Timestamp.class
                                || type == java.sql.Time.class || type == java.sql.Date.class) {
                            // 刻意响亮失败：util/sql 日期承载不了 timestamptz 的 offset 语义，
                            // 任何折算路线都是按某个时区静默改值；模型请改用 java.time（见探针测试）
                            throw new FilamentsError("时间字段 " + fields[idx].getName() + " 声明为 " + type.getSimpleName()
                                    + "：请改用 OffsetDateTime / Instant / LocalDateTime");
                        }
                        funcs[idx] = r -> {
                            var raw = r.get(idx);
                            return type.isInstance(raw) ? raw : mapper.convertValue(raw, type);
                        };
                    }
                }
            }

            @SneakyThrows
            @Override
            public T map(Record record) {
                if (this.targets == null) {
                    initStrategies(record.fields());
                }
                var obj = ctor.newInstance();
                this.row++;
                for (var i = 0; i < this.targets.length; i++) {
                    if (this.targets[i] == null || record.get(i) == null) {
                        continue;
                    }
                    try {
                        this.targets[i].set(obj, this.values[i].apply(record));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        // 脏 jsonb、模型类型与存量数据不匹配这类错误只在特定行出现，无法本地复现；
                        // 底层的 message 与异常类型（Jackson 的 reference chain 等）经 cause 原样保留，
                        // 这里只补底层说不出的坐标：第几行、哪一列、目标类型
                        throw new FilamentsError("第 %s 行 列 %s 映射为 %s 失败".formatted(
                                this.row, this.rowFields[i].getName(), this.targets[i].getType().getSimpleName()), e);
                    }
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
            // 计数直接复用同一份 query：buildAggregation 只取条件键，rt/od/p/pc 在这里不参与，
            // 无需在调用方剥键
            var count = this.aggregation(db, Map.of("count", this.pk), copyQuery, List.of(), null);

            // 与 aggregation 中的别名规则保持一致（同一个 helper，避免两处漂移）
            var countKey = aggAlias("count", this.pk);
            var raw = count.stream().findFirst().map(r -> r.get(countKey)).orElse(null);

            // 无 group by 的 count 恒返回一行，取不到列就是别名对不上；
            // 不让 NPE（get 返回 null 再 toString）或 NumberFormatException 裸逃
            if (raw == null) {
                throw new FilamentsError("分页计数取不到列 %s，聚合实际返回: %s".formatted(
                        countKey, count.isEmpty() ? "无结果行" : count.getFirst().keySet()));
            }
            try {
                res.total = Long.valueOf(raw.toString());
            } catch (NumberFormatException e) {
                throw new FilamentsError("分页计数列 %s 不是整数: %s".formatted(countKey, raw), e);
            }
        }


        return res;
    }

    public List<T> getByIds(DSLContext db, List<String> ids, Boolean lock) {
        SelectQuery<Record> select = db.selectQuery();
        select.addSelect(asterisk());
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
     *
     * @param db
     * @param target {聚合函数: [字段]}
     * @param query  筛选条件（只取条件键：`rt`/`od`/`p`/`pc` 是行语义，不参与聚合，见 buildAggregation）
     * @param group  分组条件
     * @param sub
     * @return
     */
    public List<Map<String, Object>> aggregation(DSLContext db, Map<String, Object> target, Map<String, Object> query, List<String> group, SelectQuery sub) {
        var base = buildAggregation(db, target, query, group, sub);
        return base.fetch().intoMaps();
    }

    /**
     * 聚合查询构建（独立出来便于无连接渲染验证）
     *
     * <p>只复用查询基座（FROM + WHERE），结果形态完全由 group + target 决定：
     * `rt` 的投影要丢掉（非聚合列混进 GROUP BY 场景会直接报错），
     * `p`/`pc` 的 LIMIT/OFFSET 更要丢掉——它们会作用在聚合行上：
     * 无 group 时聚合只有一行，带 offset 就一行不剩（count 查不到）；
     * 有 group 时按页切掉分组行，返回的 count/sum 静默变小。
     * 需要给分组结果翻页的，自己用 sub 包一层再聚合。
     */
    protected SelectQuery<Record> buildAggregation(DSLContext db, Map<String, Object> target, Map<String, Object> query, List<String> group, SelectQuery sub) {
        // 无 target 且无 group 时聚合没有任何投影语义，buildBase 会兜底成 select *，
        // 对 LLM 生成的调用（漏传/拼错 target）等于放开一条无上限的全表整行扫描，直接拒绝。
        // group 非空时 select group_cols ... group by ... 行数有界（不超 distinct 组数），属合法查询，保留。
        if (target.isEmpty() && group.isEmpty()) {
            throw new FilamentsError("聚合查询缺少目标：target 与 group 不能同时为空，疑似漏传聚合函数");
        }

        var base = buildBase(db, query, specBuilder.parse(query), sub);

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
                // 防注入：字段名逐段过标识符白名单校验，拒绝结构字符拼入 SQL
                identsSafe(fieldName);
                var sqlField = fieldName.contains(".") ? jsonKeyExpr(fieldName) : columnExpr(fieldName);

                // countDistinct 先于白名单拦下：它是关键字级语法，漏给精确匹配只会被拒
                var sugar = func.trim().equalsIgnoreCase("countDistinct");
                var funcName = sugar ? "countDistinct" : funcNameSafe(func);
                var fieldExpr = sugar
                        ? "count(distinct %s)".formatted(sqlField)
                        : "%s(%s)".formatted(funcName, sqlField);

                base.addSelect(field(fieldExpr).as(aggAlias(funcName, fieldName)));
            }
        }

        return base;
    }
    // endregion
}
