package me.karboom.java.filaments;

import cn.hutool.core.util.StrUtil;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.With;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * 实例配置：命名、分隔符、函数白名单、分页默认值、JSON mapper。
 *
 * <p>不可变：7 项都是 final 字段，改配置只能经 {@code withXxx} 拿到改了一处的新副本。
 * 这不是洁癖——{@link Filaments} 构造期就把 {@link #naming} 的结果物化成列索引与 JSON 字段集，
 * 而渲染、读写路径仍在运行期当场调 {@code naming}。配置一旦允许原地改，同一个实例就会
 * 一半按旧名查索引、一半按新名拼 SQL，落到库里的是错数据而不是异常。
 * 已建好的实例要换配置，用新 config 重新建一个。
 *
 * <p>用法：{@code new FilamentsConfig().withNaming(FilamentsConfig::camel)} 传给构造函数
 * （{@code new Filaments<User>("t", config) {...}}）。不显式传的实例各自持有一份内置默认配置；
 * 需要多实例共享配置，就自己持有同一个 config 实例逐个传入——不存在应用级全局配置对象。
 *
 * <p>命名规则就是 {@link #naming} 一个函数字段（列名与 jsonb 路径段共用，
 * 假设一个系统内风格统一），默认 {@link #snake}；可整体换成 {@link #camel} /
 * {@link #lower} / {@link #asIs}，也可组合出自定义规则
 * （如加表前缀：{@code s -> "t_" + FilamentsConfig.snake(s)}）。
 * 只影响"名字怎么拼"，不影响存量数据：换规则前请确认库里列名 / jsonb 键名形态。
 */
@With
@ToString
@EqualsAndHashCode
public final class FilamentsConfig {

    /** 查询语法的默认名字分隔符 */
    public static final String DEFAULT_NAME_SPLITTER = "|";

    /** 查询语法的默认条件别名分隔符 */
    public static final String DEFAULT_ALIAS_SPLITTER = "::";

    /**
     * PostgreSQL 常用函数预设：聚合 / 数学 / 条件 / 字符串 / 日期 / JSONB。
     * 收录标准——签名必须能写成 {@code func(表达式, 整数...)}：
     * 首参是链入的列/路径表达式，其余参数仅限数字字面量。
     * 因此需要字符串参数的函数（to_char、date_trunc、replace、split_part 等）
     * 即便加入白名单也无法通过参数校验，故不收录。
     */
    public static final List<String> PG_FUNCS = List.of(
            // 聚合
            "count", "sum", "avg", "min", "max",
            // 数学
            "abs", "round", "trunc", "ceil", "ceiling", "floor", "mod",
            "power", "sqrt", "sign",
            // 条件/兜底（第二参为数字时合法，如 coalesce(x, 0)）
            "coalesce", "nullif", "greatest", "least",
            // 字符串
            "lower", "upper", "trim", "btrim", "ltrim", "rtrim", "initcap",
            "reverse", "md5", "length", "char_length",
            "substr", "substring", "left", "right",
            // 日期与 JSONB
            "date", "age", "jsonb_array_length");

    /** MySQL 常用函数预设（同样的收录标准，见 {@link #PG_FUNCS}） */
    public static final List<String> MYSQL_FUNCS = List.of(
            // 聚合
            "count", "sum", "avg", "min", "max", "group_concat",
            // 数学
            "abs", "round", "truncate", "ceil", "ceiling", "floor", "mod",
            "pow", "power", "sqrt", "sign", "exp", "ln", "log",
            // 条件/兜底
            "coalesce", "ifnull", "nullif", "greatest", "least",
            // 字符串
            "lower", "upper", "trim", "ltrim", "rtrim", "reverse", "md5",
            "length", "char_length", "substr", "substring", "left", "right", "format",
            // 日期与 JSON（date_format/str_to_date 需字符串参数，未收录）
            "date", "year", "quarter", "month", "week", "day",
            "hour", "minute", "second", "unix_timestamp", "json_length");

    /**
     * SQL Server 常用函数预设（收录标准同 {@link #PG_FUNCS}；
     * datepart/datediff/format/string_agg 因需字符串/表达式后置参数未收录）。
     * greatest/least 需 2022+，trim 需 2017+。
     */
    public static final List<String> SQLSERVER_FUNCS = List.of(
            // 聚合
            "count", "sum", "avg", "min", "max",
            // 数学
            "abs", "round", "trunc", "ceiling", "floor", "power", "sqrt",
            "square", "sign", "exp", "log",
            // 条件/兜底
            "isnull", "coalesce", "nullif", "greatest", "least",
            // 字符串
            "len", "datalength", "lower", "upper", "trim", "ltrim", "rtrim",
            "reverse", "ascii", "unicode", "concat",
            "left", "right", "substring",
            // 日期与 JSON
            "year", "quarter", "month", "day", "hour", "minute", "second",
            "eomonth", "json_length", "isjson");

    /**
     * 内置默认 mapper：SNAKE_CASE 键名 + 保留时间原始 offset。
     * 自配 mapper 时请同样保留这个 disable——Jackson 3 默认会把带 offset 的时间调到上下文时区。
     */
    public static final ObjectMapper DEFAULT_MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .build();

    /**
     * 统一命名函数：模型字段名 -> 数据库列名（rt / od / 分组 / where 条件键 / 主键 / 聚合别名），
     * 同时也是 jsonb 查询路径段（{@code extra.xxx} 的 xxx）的改写规则。
     * 前提假设：一个系统内列名与 JSON 键用同一种风格；序列化侧的键风格由 {@link #mapper} 决定，需配对设置。
     */
    public final UnaryOperator<String> naming;

    /**
     * 查询语法里的名字分隔符：{@code 字段|函数(参数)|操作符}。
     * 换成别的（如 {@code #}）可以让条件键整体不带竖线，方便走 URL 编码敏感的链路；
     * 不要用 {@code .}——它和 jsonb 的点分路径（{@code extra.age}）冲突。
     */
    public final String nameSplitter;

    /**
     * 条件别名分隔符：key = {@code 别名::字段|函数链|操作符}。
     * 别名存在的意义：
     *   1. lg 表达式里 {@code ( ) ! , =} 全是结构字符，带参数的函数链（如 substr(1,2)）
     *      无法作为 key 直接写进 lg；用短别名引用即可绕开保留字符；
     *   2. Map 的键必须唯一，同一表达式需要出现多次（不同值 / 不同操作符）时，
     *      靠别名区分，避免键折叠。
     * 别名只是查询参数内部的寻址名字，永不参与 SQL 拼接。
     * 改动约束：不能与 {@code nameSplitter} 互为子串、不含 {@code .}，否则 key 解析歧义。
     */
    public final String aliasSplitter;

    /**
     * 函数链 / 聚合函数白名单（{@code SelectSpecBuilder.buildFuncChain} 精确匹配，
     * 参数仅允许数字字面量）。默认 {@link #PG_FUNCS}，可整体换成
     * {@link #MYSQL_FUNCS} / {@link #SQLSERVER_FUNCS} 或自定义列表。
     */
    public final List<String> sqlFuncList;

    /**
     * pages() 未指定 p 时的页码。
     * <p>分页数值（页码/页大小/offset/total）全链路统一 Long：int 域的 (p-1)*pc 会溢出成
     * 负数渲染进 SQL，total 超过 21 亿时 Integer.valueOf 直接炸。
     */
    public final Long page;

    /** pages() 未指定 pc 时的页大小 */
    public final Long pageSize;

    /**
     * 所有 JSON 读写（jsonb 字段编解码 + 结果行映射）共用的唯一 mapper。
     * 换序列化风格时自行用 JsonMapper.builder() 配一个，并与 {@link #naming} 配对；
     * 务必保留 disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)，参考 {@link #DEFAULT_MAPPER}。
     */
    public final ObjectMapper mapper;

    // region 构造（默认值 + withXxx 的唯一出口）

    /**
     * 内置默认配置：{@link #snake} 命名、{@code |} 与 {@code ::} 分隔符、{@link #PG_FUNCS}、
     * 页 1 / 页大小 20、{@link #DEFAULT_MAPPER}。要改任何一项请走 {@code withXxx} 取新副本。
     */
    public FilamentsConfig() {
        this(FilamentsConfig::snake, DEFAULT_NAME_SPLITTER, DEFAULT_ALIAS_SPLITTER,
                PG_FUNCS, 1L, 20L, DEFAULT_MAPPER);
    }

    /**
     * 全参构造：7 个 {@code withXxx} 复制副本时汇聚到这唯一一处，字段定型也在这里。
     * <p>入参顺序与字段声明顺序一致（Lombok 的 {@code @With} 按此顺序回传）。
     * 白名单表做 {@code List.copyOf}：调用方之后再改自己那个 List 影响不到已建好的配置；
     * {@code List.of} 产出的表原样返回同一实例，默认值不额外分配。
     */
    private FilamentsConfig(UnaryOperator<String> naming, String nameSplitter, String aliasSplitter,
                            List<String> sqlFuncList, Long page, Long pageSize, ObjectMapper mapper) {
        this.naming = naming;
        this.nameSplitter = nameSplitter;
        this.aliasSplitter = aliasSplitter;
        this.sqlFuncList = List.copyOf(sqlFuncList);
        this.page = page;
        this.pageSize = pageSize;
        this.mapper = mapper;
    }

    // endregion

    // region 命名备选实现（幂等：输入已是目标风格时结果不变，camel 除外按 hutool 语义）

    /** 驼峰转小写下划线：wechatId -> wechat_id；连续大写同样降格（userID -> user_id，USER -> user） */
    public static String snake(String fieldName) {
        return StrUtil.toUnderlineCase(fieldName).toLowerCase(Locale.ROOT);
    }

    /** 下划线转小驼峰：wechat_id -> wechatId（对已是驼峰的输入幂等） */
    public static String camel(String fieldName) {
        return StrUtil.toCamelCase(fieldName);
    }

    /** 只转小写，不插下划线：wechatId -> wechatid */
    public static String lower(String fieldName) {
        return fieldName.toLowerCase(Locale.ROOT);
    }

    /** 逐字使用，不做任何改写 */
    public static String asIs(String fieldName) {
        return fieldName;
    }

    // endregion
}
