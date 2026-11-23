package me.karboom.java.filaments;

import cn.hutool.core.util.StrUtil;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.ToString;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.PropertyNamingStrategy;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * 实例配置：命名、分隔符、函数白名单、分页默认值、JSON mapper。
 *
 * <p>字段全部 public 可写，安全靠<b>快照约定</b>兜住：{@link Filaments} 构造时 clone 传入的
 * config，实例持有私有副本——之后调用方怎么改自己手里那份，都影响不到已建好的实例。
 * {@code Filaments} 构造期会把命名结果物化成列索引与 JSON 字段集，渲染期又当场按 config
 * 拼名字，所以"改配置"只有两条正路：改完的原样 clone 交给<em>新建</em>的实例；
 * 或重建实例。唯一能撕开两套名字的路径是从子类里原地改 {@code filament.config}
 * （字段是 protected 而非 public，就是在堵这条路的顺手路），禁止这样做。
 *
 * <p>用法（改一项 = 复制后赋值）：
 * <pre>{@code
 * var cfg = new FilamentsConfig().clone();
 * cfg.pageSize = 7L;
 * var filament = new Filaments<User>("t", cfg) { ... };
 * }</pre>
 *
 * <p>命名默认<b>派生自 {@link #mapper} 的 PropertyNamingStrategy</b>（内置
 * {@link #DEFAULT_MAPPER} 是 Jackson SNAKE_CASE，效果即 {@link #snake}）——
 * "mapper 写进去的键"与"查询用的键"同源于同一个策略对象，jsonb 点分通道的读写一致
 * 是构造保证，见 {@link #naming(String)}。换序列化风格只改 {@code mapper}，命名自动跟上；
 * 要把命名从 mapper 解耦才给 {@code naming} 字段赋值显式绑定（置回 null 即恢复派生）。
 * 显式规则若含 Jackson 写不出来的改写（前缀等），jsonb 点分查询会静默失配——
 * {@code #>>} 对不存在的键返回 NULL 而不是报错，一致性自此自担。
 *
 * <p>列名与 jsonb 路径段共用同一个命名结果（假设一个系统内风格统一）。
 * {@link #snake} / {@link #camel} / {@link #lower} / {@link #asIs} 与自组合规则只作显式绑定备选；
 * 换规则只影响"名字怎么拼"，不影响存量数据：换之前请确认库里列名 / jsonb 键名形态。
 */
@ToString
@EqualsAndHashCode
public final class FilamentsConfig implements Cloneable {

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

    // region 配置项（public 可写；交给实例前先 clone，见类注释的快照约定）

    /**
     * naming 的<b>显式绑定槽</b>，null = 生效值按 {@link #mapper} 现派生。
     * <p>读生效名字一律走 {@link #naming(String)}，不要直接消费本字段——它的 null 是语义
     * （"未绑定"），不是缺陷；把字段值当函数用的人会在默认配置上拿到 NPE。
     */
    public UnaryOperator<String> naming;

    /**
     * 查询语法里的名字分隔符：{@code 字段|函数(参数)|操作符}。
     * 换成别的（如 {@code #}）可以让条件键整体不带竖线，方便走 URL 编码敏感的链路；
     * 不要用 {@code .}——它和 jsonb 的点分路径（{@code extra.age}）冲突。
     */
    public String nameSplitter = DEFAULT_NAME_SPLITTER;

    /**
     * 条件别名分隔符：key = {@code 别名::字段|函数链|操作符}。
     * 别名存在的意义：
     *   1. lg 表达式里 {@code ( ) ! ,} 是结构字符、{@code =} 是保留字符（内联值语法已禁用，
     *      带 {@code =} 的 token 直接报错），带参数的函数链（如 substr(1,2)）
     *      无法作为 key 直接写进 lg；用短别名引用即可绕开保留字符；
     *   2. Map 的键必须唯一，同一表达式需要出现多次（不同值 / 不同操作符）时，
     *      靠别名区分，避免键折叠。
     * 别名只是查询参数内部的寻址名字，永不参与 SQL 拼接。
     * 改动约束：不能与 {@code nameSplitter} 互为子串、不含 {@code .}，否则 key 解析歧义。
     */
    public String aliasSplitter = DEFAULT_ALIAS_SPLITTER;

    /**
     * 函数链 / 聚合函数白名单（{@code SelectSpecBuilder.buildFuncChain} 精确匹配，
     * 参数仅允许数字字面量）。默认 {@link #PG_FUNCS}，可整体换成
     * {@link #MYSQL_FUNCS} / {@link #SQLSERVER_FUNCS} 或自定义列表。
     * <p>自定义列表请交不可变内容（{@code List.copyOf(...)}）：字段可写时代没有构造期
     * 防御性拷贝的位置了，赋值之后再改自己那个 List 会直接改到配置生效值。
     */
    public List<String> sqlFuncList = PG_FUNCS;

    /**
     * pages() 未指定 p 时的页码。
     * <p>分页数值（页码/页大小/offset/total）全链路统一 Long：int 域的 (p-1)*pc 会溢出成
     * 负数渲染进 SQL，total 超过 21 亿时 Integer.valueOf 直接炸。
     */
    public Long page = 1L;

    /** pages() 未指定 pc 时的页大小 */
    public Long pageSize = 20L;

    /**
     * 所有 JSON 读写（jsonb 字段编解码 + 结果行映射）共用的唯一 mapper。
     * <p>换序列化风格时自行用 {@code JsonMapper.builder()} 配一个赋值进来，
     * 务必保留 {@code disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)}，
     * 参考 {@link #DEFAULT_MAPPER}；{@code naming} 槽未显式绑定时，命名随本字段自动重派生。
     */
    public ObjectMapper mapper = DEFAULT_MAPPER;

    // endregion

    // region 命名（生效值读取时解析，联动不住在任何复制代码里）

    /**
     * 模型字段名 -> 生效名字（数据库列名 / jsonb 路径段共用，假设一个系统内风格统一）。
     * <p>消费入口是方法而不是字段：生效值与 mapper 有联动（未绑定时现派生），
     * 存在构造期就变成"字段直调"的形态要么丢联动、要么丢出身信息，两头都堵——
     * 解析放读取时，联动就只是这几行，复制机制（{@link #clone()}）无需知道它的存在。
     * <p>作用于：条件键、rt、od、分组字段、主键 pk、写侧列名、聚合别名与 jsonb 路径段。
     * 未绑定 {@link #naming} 时按当前 mapper 现派生（{@link #namingFrom}），内置默认效果即
     * Jackson SNAKE_CASE；派生是两次 getter 加一个 lambda 包装，相对一次 SQL 渲染可忽略。
     */
    public String naming(String fieldName) {
        var bound = naming;
        if (bound != null) {
            return bound.apply(fieldName);
        }
        return namingFrom(mapper).apply(fieldName);
    }

    /**
     * 从 mapper 的 PropertyNamingStrategy 派生命名函数。
     * <p>动机：jsonb 列"写进去的键"由 mapper 决定、"查询用的键"由命名决定，
     * 两个独立旋钮一旦漂移（如命名带前缀而 Jackson 策略表达不了前缀），
     * 点分查询会静默失配（{@code #>>} 对缺失键返回 NULL）。让查询侧调<b>同一个</b>策略对象，
     * 漂移在结构上不可能发生，Jackson 与 hutool 的边角差异（如 {@code userID}）也一并消失。
     * <p>流程：读 {@code mapper.serializationConfig().getPropertyNamingStrategy()}——
     * 未配置策略的 mapper 返回 null（即 Jackson 不改键名），对应 {@link #asIs}；
     * 否则包一层 {@code nameForField(sc, null, seg)}。
     * <p>对自定义策略的唯一约束：{@code nameForField} 不得解引用 Annotated member
     * （此处传 null）——内置策略都只消费名字参数，第三方策略若依赖 member 会在查询渲染期 NPE。
     */
    public static UnaryOperator<String> namingFrom(ObjectMapper mapper) {
        var sc = mapper.serializationConfig();
        PropertyNamingStrategy strategy = sc.getPropertyNamingStrategy();
        return strategy == null ? FilamentsConfig::asIs : seg -> strategy.nameForField(sc, null, seg);
    }

    /**
     * 驼峰转小写下划线：wechatId -> wechat_id；连续大写同样降格（userID -> user_id，USER -> user）。
     * 备选命名实现（幂等：输入已是目标风格时结果不变，camel 除外按 hutool 语义），
     * 仅作 {@link #naming} 槽的显式绑定备选；默认命名走 {@link #namingFrom}，不经过这里。
     */
    public static String snake(String fieldName) {
        return StrUtil.toUnderlineCase(fieldName).toLowerCase(Locale.ROOT);
    }

    /** 下划线转小驼峰：wechat_id -> wechatId（对已是驼峰的输入幂等）；用途见 {@link #snake} */
    public static String camel(String fieldName) {
        return StrUtil.toCamelCase(fieldName);
    }

    /** 只转小写，不插下划线：wechatId -> wechatid；用途见 {@link #snake} */
    public static String lower(String fieldName) {
        return fieldName.toLowerCase(Locale.ROOT);
    }

    /** 逐字使用，不做任何改写；用途见 {@link #snake} */
    public static String asIs(String fieldName) {
        return fieldName;
    }

    // endregion

    /**
     * 浅拷贝出一份可独立修改的副本——"改配置"的标准姿势（见类注释的快照约定）。
     * <p>浅拷贝够用的原因：引用字段本身全是不可变语义（mapper 建好即固化、函数引用、
     * 默认白名单为 List.of），标量字段逐字复制；日后新增配置项也自动跟着走，
     * 不存在 wither 清单要逐字段维护的漂移点。
     */
    @SneakyThrows
    @Override
    public FilamentsConfig clone() {
        return (FilamentsConfig) super.clone();
    }
}
