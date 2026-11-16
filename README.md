<h1 align="center">Filaments Java</h1>

<p align="center">
  <i>为自然语言与 vibe coding 而生的轻量 Java ORM</i>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.karboom/filaments-java">
    <img src="https://img.shields.io/maven-central/v/io.github.karboom/filaments-java?label=Maven%20Central&logo=apachemaven&logoColor=white" alt="Maven Central">
  </a>
  <a href="https://javadoc.io/doc/io.github.karboom/filaments-java">
    <img src="https://img.shields.io/badge/javadoc-available-blueviolet?logo=readthedocs&logoColor=white" alt="Javadoc">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/license-Apache--2.0-blue?logo=apache&logoColor=white" alt="Apache-2.0 License">
  </a>
  <img src="https://img.shields.io/badge/Java-22%2B-orange?logo=openjdk&logoColor=white" alt="Java 22+">
  <a href="https://github.com/karboom/filaments-java">
    <img src="https://img.shields.io/github/stars/karboom/filaments-java?style=flat&logo=github&label=Stars" alt="Stars">
  </a>
  <a href="https://github.com/karboom/filaments-java/compare">
    <img src="https://img.shields.io/badge/PRs-welcome-brightgreen?logo=contribute&logoColor=white" alt="PRs Welcome">
  </a>
</p>

一个轻量级的 Java ORM 库，提供了一组语法可以将查询字符串直接转换为 SQL 查询。它的接口是**为自然语言而生的**：查询条件就是「字段|函数|操作符=值」这样的短字符串，与人类描述需求的语序几乎一一对应，因此也非常适合作为 **AI / LLM 生成代码（vibe coding）的数据层目标库**。

## 特性

- 🔄 将查询字符串自动转换为 SQL 查询
- 🔍 支持丰富的查询操作符和函数
- 🗣️ 自然语言友好：查询 DSL 即字符串，LLM 无需理解复杂 builder API 即可准确生成
- 🤖 Vibe coding 适配：统一的方法签名 + 约定式字段映射，AI 一次对话即可完成数据访问层
- 📦 易于集成到现有项目中
- ⚡ 高性能的数据访问层
- 🛠️ 灵活的扩展机制

## 为什么对自然语言 / Vibe Coding 友好

传统 ORM 让 LLM 写查询时，需要它精确记忆大量 builder API、类型和链接调用；任何一处拼错都会编译失败。Filaments 把查询收敛成**一种高度规律、可自然表达的字符串语法**：

1. **需求到查询几乎零翻译成本**
   "查询年龄大于 18 的北京用户，按注册时间倒序" 直接映射为：

   ```java
   filaments.getByPages(db, new HashMap<>() {{
       put("age|gt", 18);
       put("city", "北京");
       put("od", "-create_time");
   }});
   ```

   `字段|操作符=值` 与中文/英文的描述语序同构（字段→条件→值），LLM 最容易学会的就是这种模式化短字符串，出错率显著低于链式 API。

2. **参数是纯数据（Map / String），不是代码**
   查询条件可以由 LLM 以 JSON 形式输出再直接传入，天然适合 Function Calling / Tool 参数场景，也便于服务端做校验与回放。复杂逻辑树用 `lg` 表达式（如 `!(a=1,b=2)`）描述，且提供 `toTreeString()` 树形打印，AI 生成后可视化自检。

3. **强约定，弱配置**
   驼峰字段自动转下划线列名、JSON 列自动序列化、`p/pc/pg` 分页、`rt` 指定返回字段——LLM 不需要生成任何映射配置或样板代码，命名约定本身即是"提示词"。

4. **接口面极小，一次对话即可上手**
   核心入口只有 `create / updateByIds / deleteByIds / get / getByPages / getByIds / aggregation` 几个方法，第一个参数统一是 `DSLContext`。AI 只需少量示例（few-shot）即可覆盖整个库的用法，生成的代码风格高度一致、可预测。

5. **安全边界清晰**
   所有值走占位符绑定；字段名/排序键/聚合分组经标识符白名单校验，函数链仅允许 `FilamentsConfig.sqlFuncList` 白名单函数且参数限数字字面量，JSON 路径段逐段校验并转义。非法输入直接抛错而非拼入 SQL，LLM 生成的查询不会引入拼接式 SQL 注入风险，适合作为"让 AI 直接操作数据查询"的落地层。

## 快速开始

### 安装

确保你的项目使用 Gradle 或 Maven 构建，然后添加依赖：

#### Gradle

```gradle
dependencies {
    implementation 'io.github.karboom:filaments-java:2.1.0'
}
```

#### Maven

```xml
<dependency>
    <groupId>io.github.karboom</groupId>
    <artifactId>filaments-java</artifactId>
    <version>2.1.0</version>
</dependency>
```

### 基本用法

```java
// 一个实例对应一张表：Filaments 是抽象类，模型类型取自匿名子类的泛型参数，
// 且必须实现唯一抽象方法 initValidator（yavi 校验器入口，无校验需求时返回空 builder 即可）
var userFilament = new Filaments<User>("user") {
    @Override
    public ValidatorBuilder<User> initValidator() {
        return new ValidatorBuilder<>();
    }
};

// 使用查询字符串进行查询
var users = userFilament.get(db, new HashMap<>() {{
    put("age|in", List.of(22, 23));
}});

// 分页查询：返回 PageResult（list + total）；total 仅在传入 pg 时计算
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
| od       | 指定排序规则                     | Null   | ?id,-height  | ``` order by `id` asc, `height` desc``` |
| pg       | 是否需要计算分页(仅 getByPages 生效) | 0      | ?pg=1        | ``` count(*) alongside limit ? offset ? ``` |

### 字段表达式

#### 格式

```
字段名|函数(参数)...|操作符=数值
```

> 约束：字段名须为合法标识符（点分路径如 `data.type` 逐段校验）；函数必须命中白名单 `FilamentsConfig.sqlFuncList`（默认内置 PG 常用函数集 `FilamentsConfig.PG_FUNCS`，另有 `MYSQL_FUNCS` / `SQLSERVER_FUNCS` 预设可整体替换或自行增删），参数仅支持数字字面量；违反即抛错。

> 命名约定：条件键、`rt`、`od`、分组字段、主键 `pk` 一律按「驼峰 → 小写下划线」归一化（`wechatId` → `wechat_id`，连续大写同样降格：`userID` → `user_id`），因此写驼峰或写库里的下划线名都指向同一列。点分 jsonb 路径的**段 0 按列名规则**归一化，其后的路径段按 JSON 键规则归一化，所以 `extra.totalCount` 与 `extra.total_count` 命中同一个键。数组下标两种写法等价：`data[0].type` 与 `data.0.type` 都指向第一个元素（方括号内只接受纯整数，其余一律抛错）。两套规则都可配置，见[命名与配置](#命名与配置)。

#### 示例

| 查询字符串                                        | 对应SQL                                                     |
| ------------------------------------------------- | ----------------------------------------------------------- |
| ?type=dog                                         | ``` where `type` = 'dog' ```                                |
| ?type&#124;in=dog,cat                             | ``` where `type` in ( 'dog', 'cat') ```                     |
| ?type&#124;substr(1,2)&#124;in=dog,cat            | ``` where substr(`type`, 1, 2) in ( 'dog', 'cat')```        |
| ?type&#124;lower&#124;substr(1,2)&#124;in=dog,cat | ``` where substr(lower(`type`), 1, 2) in ( 'dog', 'cat')``` |
| ?data.type=dog                                    | ``` where `data` #>> '{type}' = 'dog' ```                   |
| ?data[0].type=cat                                 | ``` where `data` #>> '{0,type}' = 'cat' ```                 |
| ?data[0]=dog                                      | ``` where `data` #>> '{0}' = 'dog' ```                      |
| ?data.0.type=cat                                  | 同上（下标两种写法归一化为同一形态）                          |

#### 条件别名

`lg` 表达式里 `(` `)` `!` `,` `=` 全是保留字符，而函数链键（如 `extra.age|substr(1,2)|ge`）本身含括号和逗号；同时 Map 的键必须唯一，同一表达式需要出现两次（不同值或不同操作符）时会互相覆盖。

因此条件键可以加别名前缀：`别名::字段|函数链|操作符`，`lg` 里只写短别名，值仍留在参数中。

```java
var users = filaments.get(db, new HashMap<>() {{
    put("lowAge::extra.age|substr(1,2)|ge", "5");   // substr(extra->>'$.age', 1, 2) >= 5
    put("highAge::extra.age|substr(3,4)|le", "10"); // 同字段不同函数链
    put("dup1::name|lower", "ann");                 // 同一表达式出现两次（隐式 EQ）
    put("dup2::name|lower", "bob");
    put("lg", "(!(lowAge,highAge),dup1,dup2)");     // (lowAge OR highAge) AND dup1 AND dup2
}});
```

> 约束：`lg` 表达式整体必须由一对括号包裹；别名须为合法标识符（字母/下划线开头，仅字母数字下划线）且不得重复；引用不存在或非法的别名会抛错。别名只用于参数寻址，永不参与 SQL 拼接。

### 操作符

操作符 token 精确匹配且区分大小写（就是下表里的驼峰写法）。末段不在表中时按「隐式 `eq` + 函数链末段」处理，因此拼错的操作符会被函数白名单拒掉，而不是静默改变语义。

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

> `isNull` / `isNotNull` 不取参数：键上绑的值（留空、`=1`、`=任意`）一律忽略，因为谓词没有比较对象。它们同样可以接在函数链后面（`extra.age|substr(1,2)|isNull`）。取反不需要额外操作符，`lg` 里写 `!(name|isNull)` 即可。

### 命名与配置

所有可调项都在 `FilamentsConfig` 上（旧的 `Filaments` 静态兼容字段已全部移除）。

模型字段名到「数据库列名」和「JSON 键名」的转换由**同一个命名函数**控制（一个系统内风格统一）。规则本身就是函数——库提供 `snake` / `camel` / `lower` / `asIs` 四个备选实现，可整体替换，也可组合出自定义规则：

```java
// FilamentsConfig.snake / camel / lower / asIs   （asIs = 声明什么用什么）

// 配置是普通对象：自己 new 一份传给各实例；多实例传同一个对象即等效"全局配置"
// （库不再提供 FilamentsConfig.defaults 静态实例；不传 config 的实例各持一份内置默认）
var config = new FilamentsConfig();
config.naming = FilamentsConfig::camel;
// 自定义组合示例：统一加表前缀
// config.naming = s -> "t_" + FilamentsConfig.snake(s);

var filament = new Filaments<User>("filaments_camel", config) {
    @Override
    public ValidatorBuilder<User> initValidator() { ... }
};
```

| 函数 | `wechatId` | `userID` | `wechat_id` | JSON 侧对应 Jackson 策略 |
| --- | --- | --- | --- | --- |
| `snake`（默认） | `wechat_id` | `user_id` | `wechat_id` | `SNAKE_CASE` |
| `camel` | `wechatId` | `userId` | `wechatId` | `LOWER_CAMEL_CASE` |
| `asIs` | `wechatId` | `userID` | `wechat_id` | 不改写 |
| `lower` | `wechatid` | `userid` | `wechat_id` | 全小写 |

- `naming` 作用于：条件键、`rt`、`od`、分组字段、主键 `pk`、`create`/`update` 的列名、聚合别名，以及 jsonb 查询路径段（`extra.xxx` 的 `xxx`）。
- 聚合别名由列名派生：`count(wechatId)` → `countWechatId`，`countDistinct(id)` → `countDistinctId`。
- 换 `naming` 不会迁移存量 jsonb / 列名数据；旧数据请用实例级配置或一次性刷数据。
- 序列化只认 `config.mapper` 这一个实例（默认内置 SNAKE 风格）。库不提供 mapper 工厂：换风格自己 `JsonMapper.builder().propertyNamingStrategy(...).disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).build()`——disable 别丢，否则带 offset 的时间会被静默调到本地时区；并与 `naming` 配对（默认同为 snake，天然一致）。

其余可调项（都在同一个 config 对象上，按需共享或各建各的）：

| 配置项 | 默认值 | 作用 |
| --- | --- | --- |
| `nameSplitter` | `"\|"` | 查询语法里 `字段\|函数\|操作符` 的分隔符 |
| `aliasSplitter` | `"::"` | 条件别名寻址分隔符（`别名::字段\|函数\|操作符`），永不进 SQL |
| `sqlFuncList` | `PG_FUNCS`（另有 `MYSQL_FUNCS` / `SQLSERVER_FUNCS`） | 函数链与聚合函数白名单，可整体换预设或增删。操作符 token 是固定集合（见上方操作符表），不可配置 |
| `page` / `pageSize` | 1 / 20 | `getByPages()` 未传 `p` / `pc` 时的默认分页 |

### 类型与 JSON 约定

值不靠手工 setter/getter 映射：构造期把模型字段分类后，写侧 JSON 字段序列化成 JSON 文本、其余原样绑定；读侧（doQuery）按列直接映射进对象——不再有"整行转 JSON 再解析"的往返，只有 JSON 字段的值会过一遍 Jackson。模型字段禁止声明为 `Object`（构造期抛错）：jsonb 请用 `Map<String,Object>` 或具体 POJO。JSON 键名的风格由 [`naming`](#命名与配置)（路径段）与 `config.mapper`（序列化）配对决定（默认同为 snake）。

| 模型字段类型 | 写入 | 读取 |
| --- | --- | --- |
| `String` / 数字 / `BigDecimal` / `java.time.*` / `boolean` / `byte[]` / `UUID` | 原样绑定（不套 JSON） | 列级映射：可赋值直取，否则 Jackson/jOOQ 单值转换；时间列走 jOOQ 转换器 |
| `java.util.Date` / `java.sql.*` 声明的时间字段 | 原样绑定 | 读取时响亮报错（timestamptz 的 offset 语义无法无损落到这些类型，请改用 `java.time`） |
| 枚举 | 存 `name()`（如 `A`） | `A` → 枚举 |
| `List` / `Set` / `Map` / 嵌套对象 | 序列化成 JSON 文本写入（构造期判定，不看运行时值） | 投影带 JSONB 类型/元数据，直接得到对象/数组节点 |

> JSON 与否完全由构造期的字段分类决定：`rt` 投影会据此补 `JSONB` 类型，读取直接拿到对象/数组节点，不再做字符串嗅探；文本列里恰好以 `{`、`[` 开头的值（如 `{"a":1}`）始终原样读出，不会让整个查询失败。

> 库里已有的驼峰键 jsonb（别的系统写的）在默认 `SNAKE` 下会被静默忽略（读成 null）：给该表配一个 `naming = FilamentsConfig::asIs`（或 `::camel`）的实例即可对上——注意命名已合并，列名解析会一并改变，仅适用于整库同风格的表。显式 `@JsonProperty("originalKey")` 命名的字段不受命名策略影响（Jackson 3 语义）。

## 高级用法

### 子查询

`get` 没有 `sub` 重载；子查询有两个真实入口：

1. `aggregation` 自带 `sub` 参数（传 `null` 即普通表）：

```java
var stat = userFilament.aggregation(db,
        Map.of("count", "id"),      // 聚合目标 {聚合函数: 字段}
        Map.of("role", "admin"),    // 筛选条件（同查询字符串语法）
        List.of("type"),            // 分组字段
        buildMySubQuery(db));       // 子查询
```

2. 其他查询在子类里走 protected 的 `buildSelect` + `doQuery` 自建入口：

```java
public class UserFilaments extends Filaments<User> {
    public UserFilaments() {
        super("user");
    }

    @Override
    public ValidatorBuilder<User> initValidator() {
        return new ValidatorBuilder<>();
    }

    public List<User> getWithSub(DSLContext db, Map<String, Object> query, SelectQuery<Record> sub) {
        return doQuery(buildSelect(db, query, sub), false);
    }
}
```

### 自定义扩展

继承 `Filaments` 类（构造器第一参数是表名；模型类型取自 `extends` 的泛型参数），扩展自定义方法：

```java
public class UserFilaments extends Filaments<User> {
    public UserFilaments() {
        super("user");
    }

    @Override
    public ValidatorBuilder<User> initValidator() {
        return new ValidatorBuilder<>();
    }

    public List<Record> getByOrgId(DSLContext db, Long orgId) {
        // 需要时随时落回原生 jOOQ
        return db.select().from(table("user")).where(field("org_id").eq(orgId)).fetch();
    }
}
```

## 常见问题

### 如何支持更复杂的 SQL

1. 在数据库中编写视图
2. 通过 `aggregation` 的 `sub` 参数传入构造的子查询（或在子类里用 `buildSelect` 自建带子查询的入口）
3. 继承 `Filaments` 类，扩展自定义方法，直接使用注入的 `DSLContext` 编写原生语句

### 为什么第一个参数都是 DSLContext

1. 根据业务需求灵活选择数据库连接（主库或从库）
2. 灵活选择事务上下文

## 贡献

欢迎提交 Issue 和 Pull Request！

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
