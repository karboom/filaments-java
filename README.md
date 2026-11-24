<h1 align="center">Filaments Java</h1>

<p align="center">
  <i>为自然语言与 vibe coding 而生的轻量 Java ORM</i>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.karboom/filaments-java"><img src="https://img.shields.io/maven-central/v/io.github.karboom/filaments-java?label=Maven%20Central&logo=apachemaven&logoColor=white" alt="Maven Central"></a>
  <a href="https://javadoc.io/doc/io.github.karboom/filaments-java"><img src="https://img.shields.io/badge/javadoc-available-blueviolet?logo=readthedocs&logoColor=white" alt="Javadoc"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue?logo=apache&logoColor=white" alt="Apache-2.0 License"></a>
  <img src="https://img.shields.io/badge/Java-22%2B-orange?logo=openjdk&logoColor=white" alt="Java 22+">
  <a href="https://github.com/karboom/filaments-java"><img src="https://img.shields.io/github/stars/karboom/filaments-java?style=flat&logo=github&label=Stars" alt="Stars"></a>
  <a href="https://github.com/karboom/filaments-java/compare"><img src="https://img.shields.io/badge/PRs-welcome-brightgreen?logo=contribute&logoColor=white" alt="PRs Welcome"></a>
</p>

构建在 jOOQ 之上的轻量 ORM：查询条件是「字段|函数|操作符=值」这样的短字符串，与人类描述需求的语序同构——人好写，LLM 也好生成。

## 为什么适合自然语言 / vibe coding

- **零翻译成本**："年龄大于 18 的北京用户，按注册时间倒序" 就是 `age|gt=18`、`city=北京`、`od=-create_time`；`字段→操作符→值` 与中英文语序同构，比链式 builder API 出错率低得多。
- **参数是数据不是代码**：条件以 `Map` / JSON 传入，天然适配 Function Calling，也便于服务端校验与回放；复杂逻辑用 `lg` 表达式（`!(a,b)`），`LogicNode#toTreeString()` 打印出来即可自检。
- **接口小、约定强**：入口只有 `create / updateByIds / deleteByIds / get / getByPages / getByIds / aggregation`，第一参数统一 `DSLContext`；驼峰自动转下划线、JSON 列自动序列化、`p/pc/pg` 分页，不需要映射配置与样板代码。
- **安全边界清晰**：值全走占位符绑定；字段名/排序键/分组字段逐段过标识符白名单，函数只认 `FilamentsConfig.sqlFuncList` 且参数限数字字面量；非法输入抛错而不是拼进 SQL。

## 快速开始

```gradle
implementation 'io.github.karboom:filaments-java:2.2.0'
```

```xml
<dependency><groupId>io.github.karboom</groupId><artifactId>filaments-java</artifactId><version>2.2.0</version></dependency>
```

Java 22+，PostgreSQL。

```java
// 一个实例对应一张表：Filaments 是抽象类，模型类型取自 extends Filaments<User> 的泛型实参；
// initValidator 是唯一抽象方法（yavi 校验入口，无校验需求时返回空 builder）
var userFilament = new Filaments<User>("user") {
    @Override
    public ValidatorBuilder<User> initValidator() {
        return new ValidatorBuilder<>();
    }
};

var users = userFilament.get(db, new HashMap<>() {{
    put("age|in", List.of(22, 23));
}});

// 分页：返回 PageResult（list + total），total 仅在传入 pg 时计算
var page = userFilament.getByPages(db, new HashMap<>() {{
    put("name", "x");
    put("pg", 1);
}});
```

## 查询字符串语法

### 保留关键字

| 字段名称 | 功能                             | 默认值 | 查询字符串   | 对应SQL                                 |
| -------- | -------------------------------- | ------ | ------------ | --------------------------------------- |
| rt       | 指定返回字段（仅列名，不支持 jsonb 路径/下标） | *      | ?rt=name,age | ```select `name`, `age` ```             |
| p        | 指定当前页码                     | 1      | ?p=3         | ```limit 20 offset 40```                |
| pc       | 指定页内行数                     | 20     | ?pc=18       | ```limit 18 offset 0```                 |
| od       | 指定排序规则（`-` 为降序）        | Null   | ?od=id,-height  | ``` order by `id` asc, `height` desc``` |
| pg       | 算不算总数——**只看键在不在，值不参与判断**（仅 getByPages 生效） | 不传 | ?pg=1 | ``` count(*) 不带 limit/offset，行结果才分页 ``` |

> `p` 与 `pc` 各自独立可用，缺的那个取 `config.page` / `config.pageSize` 默认：`?p=3` 就是 `limit 20 offset 40`，`?pc=18` 就是 `limit 18 offset 0`。**两个键都不给时不分页**——`get` 因此仍是全量取数入口，要"永远分页"就用 `getByPages`（它两个键都补默认值）。
>
> 这五个键只描述"行结果"，因此只对 `get` / `getByPages` / `getByIds` 生效；`aggregation` 只取其中的条件（含 `lg` / `raw`），同一份 query 直接喂给两者是安全的（分页不会截断 count，投影不会混进 GROUP BY）。
>
> `pg` 是"存在即开启"：只要键在就算总数，`pg=0`、`pg=false` 也一样算；不想要 count 就别带这个键。
>
> `rt` 每段只当列名（不校验、不认 jsonb 路径与函数链）：库里没有的列由数据库报错，模型没声明的列静默跳过。

### 字段表达式

```
字段名|函数(参数)...|操作符=数值
```

- 字段名须为合法标识符（点分路径如 `data.type` 逐段校验）；函数须命中白名单 `FilamentsConfig.sqlFuncList`（默认 `PG_FUNCS`，另有 `MYSQL_FUNCS` / `SQLSERVER_FUNCS`），参数仅支持数字字面量；违反即抛错。
- `|` 是**同一个键内**的分段符，不是"或"：`name|lower|like` 是一个条件 `lower(name) like ?`，只有末段命中操作符表时才是操作符，其余段都是函数链。OR / AND 走 `lg`（`(a,b)`、`!(a,b)`，`!` 只是把括号组标成 OR 连接，不做逻辑取反）；取反只存在于谓词级——用 `ne` / `notIn` / `notBetween` / `notLike` / `isNotNull`，整组 NOT 暂不支持。
- 名字一律经 `config.naming` 归一化（默认驼峰 → 小写下划线，`userID` → `user_id`），写驼峰或写库里的下划线名都指向同一列。点分路径的**段 0 按列名规则**、其后按 JSON 键规则，故 `extra.totalCount` 与 `extra.total_count` 命中同一个键；数组下标 `data[0].type` 与 `data.0.type` 等价（括号内只接受纯整数）。详见[命名与配置](#命名与配置)。

| 查询字符串                                        | 对应SQL                                                     |
| ------------------------------------------------- | ----------------------------------------------------------- |
| ?type=dog                                         | ``` where `type` = 'dog' ```                                |
| ?type&#124;in=dog,cat                             | ``` where `type` in ( 'dog', 'cat') ```                     |
| ?type&#124;substr(1,2)&#124;in=dog,cat            | ``` where substr(`type`, 1, 2) in ( 'dog', 'cat')```        |
| ?type&#124;lower&#124;substr(1,2)&#124;in=dog,cat | ``` where substr(lower(`type`), 1, 2) in ( 'dog', 'cat')``` |
| ?data.type=dog                                    | ``` where `data` #>> '{type}' = 'dog' ```                   |
| ?data[0].type=cat                                 | ``` where `data` #>> '{0,type}' = 'cat' ```                 |
| ?data[0]=dog                                      | ``` where `data` #>> '{0}' = 'dog' ```                      |

#### 条件别名

`lg` 表达式里 `(` `)` `!` `,` `=` 全是保留字符，而函数链键（`extra.age|substr(1,2)|ge`）自带括号和逗号；同时 Map 的键必须唯一，同一表达式需要出现两次（不同值或不同操作符）时会互相覆盖。因此条件键可加前缀 `别名::字段|函数链|操作符`，`lg` 里只写短别名，值仍留在参数中：

```java
var users = filaments.get(db, new HashMap<>() {{
    put("lowAge::extra.age|substr(1,2)|ge", "5");   // substr(extra #>> '{age}', 1, 2) >= 5
    put("highAge::extra.age|substr(3,4)|le", "10"); // 同字段不同函数链
    put("dup1::name|lower", "ann");                 // 同一表达式出现两次（隐式 EQ）
    put("dup2::name|lower", "bob");
    put("lg", "(!(lowAge,highAge),dup1,dup2)");     // (lowAge OR highAge) AND dup1 AND dup2
}});
```

> `lg` 只做参数键的组合逻辑：token 是条件键或别名引用，值一律留在查询参数里，写内联值（`k=v`）直接抛错。顶层括号组可以带裸 token（`a`、`a,(b,c)` 都合法，并列元素按 AND 组合）；没有分隔符的并置括号组（`(a)(b)`）抛错。别名须为合法标识符且不得重复，引用不存在的别名抛错。别名只用于参数寻址，永不参与 SQL 拼接。

### 操作符

操作符 token 精确匹配且区分大小写；末段不在表中时按「隐式 `eq` + 函数链末段」处理，所以拼错的操作符会被函数白名单拒掉，而不是静默改变语义。

| 查询字符串                | 对应SQL                                              |
| ------------------------- | ---------------------------------------------------- |
| ?id&#124;ne=1             | ```  where `id` <> 1 ```                             |
| ?id&#124;gt=1             | ```  where `id` > 1 ```                              |
| ?id&#124;ge=1             | ```  where `id` >= 1 ```                             |
| ?id&#124;lt=1             | ```  where `id` < 1 ```                              |
| ?id&#124;le=1             | ```  where `id` <= 1 ```                             |
| ?id&#124;in=1,2           | ```  where `id` in (1, 2) ```                        |
| ?id&#124;notIn=1,2        | ```  where `id` not in (1, 2) ```                    |
| ?id&#124;between=1,2      | ```  where `id` between 1 and 2 ```                  |
| ?id&#124;notBetween=1,2   | ```  where `id` not between 1 and 2 ```              |
| ?id&#124;like=k%          | ```  where `id` like 'k%' ```                        |
| ?id&#124;notLike=k%       | ```  where `id` not like 'k%' ```                    |
| ?tags&#124;intersect=a    | ```  where jsonb_path_exists(`tags`, '$[*] ? (@ == $q0)', ...) ``` |
| ?name&#124;isNull=        | ```  where `name` is null ```                        |
| ?name&#124;isNotNull=     | ```  where `name` is not null ```                    |

> `isNull` / `isNotNull` 不取参数（键上绑什么都忽略，谓词没有比较对象），也可接在函数链后（`extra.age|substr(1,2)|isNull`）；两者互为反义，取反就是换成 `isNotNull`——`lg` 里的 `!(...)` 只是 OR 连接标记，不会渲染成 NOT。

### 值的形态

值全部走占位符绑定，永不进 SQL 文本；「一个键上绑几个值」由操作符决定——逗号只在天生多值的地方才是分隔符：

| 操作符 | 值个数 | String 怎么解释 |
| --- | --- | --- |
| `in` / `notIn` / `intersect` | 不限 | 按 `,` 拆成多个值（`?id&#124;in=1,2`） |
| `between` / `notBetween` | 恰好 2 | 按 `,` 拆；拆出来不是两个就抛错 |
| `eq` / `ne` / `gt` / `ge` / `lt` / `le` / `like` / `notLike` | 恰好 1 | **整串作一个值**，逗号只是普通字符 |
| `isNull` / `isNotNull` | 不取值 | 键上绑什么都忽略（也不计数，null 也不例外） |

- 单值操作符不拆逗号，含逗号的 like 模式、JSON 文本、名字直接写就行，无需转义：`?name=张,三`、`?profile={"a":1,"b":2}`。往单值操作符上绑多个值会**抛错**，不会静默只取第一个（jOOQ 对多余绑定值是悄悄丢弃的）。多模式 `like` 请用别名 + `lg`：`l1::name|like=a%`、`l2::name|like=%b`、`lg=(l1,l2)`。
- 多值操作符里某个元素本身含逗号时传 `List` / `Set`（逐元素取值，内部逗号不拆）：`Map.of("name|in", List.of("张,三", "user-2"))`。`"a,b"` 是两个值，`List.of("a,b")` 是一个含逗号的值。
- **值为 `null` 一律抛错，不折算成任何语义。** 只有 `Map`／JSON 入参能把 Java `null` 绑到键上（URL 传不进：`?name=null` 落到 map 里就是字符串 `"null"`，照常匹配文本 `'null'`）。null 绑上去渲染成 `name = ?` + NULL 绑定，三值逻辑下谓词恒不真——`eq` 静默 0 行，`notIn` 连"排除"都变成 0 行，看上去只像"库里没数据"。三条出路各归其位：查空值用 `isNull` / `isNotNull`（不取值，键上绑 null 也不受影响）；匹配文本 `'null'` 就显式传字符串；"这个条件不生效"请在拼 map 时干脆不要放这个键。多值操作符里某个元素为 null 同样抛错，消息指到第几个。
- 拆不拆、要几个都在**渲染期**决定，解析期一个元素都不动——`SelectSpec` / `toTreeString()` 里 `values` 永远只有一个元素，就是键上绑的那个东西本身。

### 命名与配置

所有可调项都在 `FilamentsConfig` 上（字段 public 可写，安全靠**快照约定**兜住：`Filaments` 构造时 `clone` 传入的 config，之后调用方怎么改自己手里那份都影响不到已建实例；多实例传同一个即等效"全局配置"，各拿一份等值副本，不传则各持一份内置默认）。改配置的标准姿势：`clone()` 后赋值几个字段，交给**新建**实例；已建实例要换配置就重建。实例内部那份副本禁止原地改（字段是 protected 就是在堵这条路）：`naming` 既在构造期物化成列索引与 JSON 字段集，又在渲染期当场参与拼列名，原地改会让两套名字分裂，写进库的是错数据。列名与 jsonb 路径段共用同一个命名，生效值在读取时现解析：未绑定时**派生自 `mapper` 的键改写策略**（内置 `DEFAULT_MAPPER` 是 Jackson SNAKE_CASE，效果即 `snake`），"mapper 写进去的键"与"查询用的键"出自同一个策略对象，构造上就对得上。换序列化风格只赋值 `mapper`，命名自动跟上；要解耦才给 `naming` 字段赋值显式绑定（置回 `null` 即恢复派生）：

```java
// 换序列化风格：只动 mapper，命名自动跟上（列名与 jsonb 键同时切到驼峰）
var config = new FilamentsConfig();
config.mapper = JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
        .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        .build();

// 解耦：显式绑定 naming（snake / camel / lower / asIs 或自组合函数），读写键一致性自此自担——
// 自组合若含 Jackson 写不出的改写（如统一加前缀），jsonb 点分查询会静默失配
// config.naming = FilamentsConfig::camel;

var filament = new Filaments<User>("filaments_camel", config) {
    @Override
    public ValidatorBuilder<User> initValidator() { ... }
};
```

下表是显式绑定 `naming` 的备选函数；默认命名不在其中，是直接包 mapper 的键策略：

| 函数 | `wechatId` | `userID` | `wechat_id` | 备注 |
| --- | --- | --- | --- | --- |
| `snake` | `wechat_id` | `user_id` | `wechat_id` | 与 `DEFAULT_MAPPER`（SNAKE_CASE）的派生效果一致 |
| `camel` | `wechatId` | `userId` | `wechatId` | hutool 语义，会把下划线拼成驼峰；Jackson 的驼峰策略只做正向改写，不还原下划线输入 |
| `asIs` | `wechatId` | `userID` | `wechat_id` | 不改写 |
| `lower` | `wechatid` | `userid` | `wechat_id` | 只转小写 |

| 配置项 | 默认值 | 作用 |
| --- | --- | --- |
| `naming` | 未绑定（派生自 `mapper`） | 作用于条件键、`rt`、`od`、分组字段、主键 `pk`、写侧列名、聚合别名与 jsonb 路径段；默认按 mapper 键策略现派生，字段赋值显式绑定后与 mapper 解耦，置回 `null` 恢复派生；不迁移存量数据 |
| `mapper` | `DEFAULT_MAPPER` | 所有 JSON 读写共用的唯一 Jackson mapper；`naming` 未绑定时命名由它的 PropertyNamingStrategy 派生，赋值换 mapper 时自动跟上 |
| `nameSplitter` | `"\|"` | 查询语法里 `字段\|函数\|操作符` 的分隔符（勿用 `.`，与 jsonb 路径冲突） |
| `aliasSplitter` | `"::"` | 条件别名寻址分隔符，永不进 SQL |
| `sqlFuncList` | `PG_FUNCS` | 函数链与聚合函数白名单，赋值整体换预设或自定义列表；自定义请交不可变内容（`List.copyOf(...)`）——字段可写时代没有构造期防御拷贝，赋值后再改你自己那个 List 会直接改到生效值；操作符 token 是固定集合，不可配置 |
| `page` / `pageSize` | 1 / 20 | 分页默认值：`getByPages()` 两键都不传时用它补齐，`get()` 只传其中一个时另一个用它补 |

- 聚合别名由列名派生：`count(wechatId)` → `countWechatId`，`countDistinct(id)` → `countDistinctId`。
- 自配 `mapper` 时务必保留 `disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)`，否则带 offset 的时间会被静默调到本地时区：`JsonMapper.builder().propertyNamingStrategy(...).disable(...).build()`。`naming` 未显式绑定过就换 `mapper` 的，命名按新策略现派生，无需手工配对。

### 类型与 JSON 约定

> 仅支持 PostgreSQL：点分 jsonb 路径渲染成 `col #>> '{path}'`，`intersect` 渲染成 `jsonb_path_exists(...)`，换函数白名单预设不会让这两条通道变得可移植。连接串须带 **`stringtype=unspecified`**（如 `jdbc:postgresql://host:5432/db?stringtype=unspecified`）：JSON 字段以 JSON 文本绑定，缺了它驱动按 VARCHAR 传参，PG 会拒 `jsonb 列 = varchar 表达式`。

构造期按声明类型把字段分成 JSON / 非 JSON 两类：写侧 JSON 字段序列化成 JSON 文本、其余原样绑定；读侧（`doQuery`）按列直接映射进对象，没有"整行转 JSON 再解析"的往返。模型字段禁止声明为 `Object` 或 jOOQ 的 `JSON`/`JSONB`（构造期抛错）：jsonb 请用 `Map<String,Object>` / POJO / `List`，要整棵裸 JSON 树就用 `JsonNode`。字段只扫模型类**自身**的 `getDeclaredFields()`，**不含父类字段**——继承来的字段既不写库也不读回（静默丢弃），公共字段请用组合或在模型类里显式声明。

| 模型字段类型 | 写入 | 读取 |
| --- | --- | --- |
| `String` / 数字 / `BigDecimal` / `java.time.*` / `boolean` / `byte[]` / `UUID` | 原样绑定（不套 JSON） | 列级映射：可赋值直取，否则 Jackson/jOOQ 单值转换；时间列走 jOOQ 转换器 |
| `java.util.Date` / `java.sql.*` 时间字段 | 原样绑定 | 读取时响亮报错（timestamptz 的 offset 语义无法无损落到这些类型，请改用 `java.time`） |
| 枚举 | 存 `name()`（如 `A`） | `A` → 枚举 |
| `List` / `Set` / `Map` / 嵌套对象 | 序列化成 JSON 文本（按声明类型判定，不看运行时值） | 取值阶梯先按 String 收 JSON 原文（`rt` 投影与 `SELECT *` 同形），再交 Jackson 反序列化成对象/数组 |
| `JsonNode`（裸 JSON 树） | 同上（也算 JSON 字段） | 直接得到 `ObjectNode` / `ArrayNode`；**不要**用 `String` 接 jsonb 列——标量声明不参与 JSON 分类，读取会响亮报错，裸文本请存 `varchar` |
| jOOQ `JSON` / `JSONB` | — | 构造期直接拒绝：读侧两条通道给的是驱动裸类型与 `JSONB` 两种形态，落不进模型 |

> JSON 与否完全由构造期分类决定，不做字符串嗅探：文本列里恰好以 `{`、`[` 开头的值始终原样读出。
>
> 库里已有的驼峰键 jsonb 在默认 `DEFAULT_MAPPER`（SNAKE_CASE）下整文档读回是 null、点分查询也静默查不到：给该表配一个 `mapper` 为 camel 策略 mapper 的实例——读写两侧同时对上，命名自动派生跟上（列名解析也随之变成驼峰，仅适用于整库同风格的表；"列 snake + 键驼峰"的混风格库点分通道无解，这是"列名与 JSON 键同风格"假设的边界）。`@JsonProperty("originalKey")` 显式命名的键不走 Jackson 策略，但查询路径段仍过命名，点分寻址这类键需 `naming = FilamentsConfig::asIs` 显式绑定。

## 校验与空值

`create`（逐行建校验器）与 `updateByIds`（一次）在写库前执行 `initValidator()`，任一条不过即抛 `FilamentsError`（yavi 第一条违规文案）；约束名与模型字段名对不上抛 `模型 X 没有字段 y`。批量先全量校验再一次性 `execute`，任一行不过则整批不写。传入的 builder 可跨实例／跨行共享，库不会改动它。

**根字段为 `null` 时，该字段所在的整条约束跳过**——这是部分更新（null = 不改写）与嵌套路径约束（父对象可能整个缺失）能工作的前提，代价是 `null` 无法表达"必填"：

| 数据形态 | 是否校验 |
| --- | --- |
| `name = null`，约束 `notBlank` | 跳过（不报错） |
| `name = ""`，约束 `notBlank` | 抛 `FilamentsError` |
| 约束 `address.city`，`address = null` | 跳过（父对象缺失） |
| 约束 `address.city`，`address != null`、`city = null` | 抛 `FilamentsError` |
| `updateByIds(ids, patch)` | 同上；`patch` 里为 null 的字段不进 SET，必填字段无法经 update 置空 |

### 必填交给数据库

列上加 `NOT NULL`：null 字段渲染成 `insert … values (default, …)` 由 PG 拒绝，一条 INSERT 是原子的（批量中某条违例则 0 行写入）。三个漏网处：

| 情况 | 结果 | 对策 |
| --- | --- | --- |
| 列是 `NOT NULL DEFAULT 'A'` | null 被默认值吃掉，看不出调用方没传 | 要求显式给值就别设默认值，或在 `before` 钩子里自查 |
| 空串 `''` | `NOT NULL` 不拦 | 用 yavi 的 `notBlank`（值非 null，约束会生效） |
| jsonb 内部键（如 `address.city` 必填） | DB 只看整列是否为 null | 在 `before` 钩子里补 |

> 约束冲突抛的是 jOOQ 的 `IntegrityConstraintViolationException`（含表名、列名与 SQL 原文），不是 `FilamentsError`；需要统一错误出口、或不想把物理表结构透给上层（异常文本会回喂给 LLM）时，在 service 层捕获。

## 扩展用法

需要子查询或原生 SQL 时，继承 `Filaments` 自建入口：

```java
public class UserFilaments extends Filaments<User> {
    public UserFilaments() {
        super("user");
    }

    @Override
    public ValidatorBuilder<User> initValidator() {
        return new ValidatorBuilder<>();
    }

    // 带子查询的行查询：用 protected 的 buildSelect + doQuery 自由组合（get 没有 sub 重载）
    public List<User> getWithSub(DSLContext db, Map<String, Object> query, SelectQuery<Record> sub) {
        return doQuery(buildSelect(db, query, sub), false);
    }

    // 需要时随时落回原生 jOOQ
    public List<Record> getByOrgId(DSLContext db, Long orgId) {
        return db.select().from(table("user")).where(field("org_id").eq(orgId)).fetch();
    }
}
```

`aggregation` 自带 `sub` 参数（传 `null` 即普通表）：

```java
var stat = userFilament.aggregation(db,
        Map.of("count", "id"),      // 聚合目标 {聚合函数: 字段}
        Map.of("role", "admin"),    // 筛选条件（同查询字符串语法）
        List.of("type"),            // 分组字段
        buildMySubQuery(db));       // 子查询
```

> 模型类型取自父类链上唯一那处 `extends Filaments<模型类>` 的泛型实参：从具体子类再派生、用匿名子类都可以，但链上的中间基类必须是非泛型具体类（`BaseF<T> extends Filaments<T>` 这类泛型中间层在构造期拒绝）。

## 常见问题

- **要跑更复杂的 SQL？** 在数据库里建视图；用 `aggregation` 的 `sub`；或在子类里自建方法直接用注入的 `DSLContext`（见[扩展用法](#扩展用法)）。
- **字段传 null 为什么没触发必填校验？** 见[校验与空值](#校验与空值)：根字段为 null 时约束整条跳过，必填请用列上的 `NOT NULL`。
- **查询条件传 null 为什么直接抛错？** 见[值的形态](#值的形态)：null 绑进 `= ?` 在 SQL 里恒不真，静默返回 0 行比报错更难查；库不猜意图，判空请用 `isNull` / `isNotNull`，要匹配文本 `'null'` 就传字符串，条件不生效就别把这个键放进 map。写侧（`create` / `updateByIds`）的 null 仍是 SQL NULL，与这里无关。
- **第一个参数为什么都是 `DSLContext`？** 便于按业务选择连接（主库／从库）与事务上下文。

## 贡献与许可

欢迎提交 Issue 和 Pull Request。版本历史与升级注意事项见 [CHANGELOG](CHANGELOG.md)。Licensed under the [Apache License, Version 2.0](LICENSE).
