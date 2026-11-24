# 更新日志

本项目遵循[语义化版本 2.0.0](https://semver.org/lang/zh-CN/)：主版本 = 不兼容变更，次版本 = 向后兼容的新能力，修订号 = 修复。
版本号定义在 [`build.gradle.kts`](build.gradle.kts) 的 `version`，发布产物是 Maven Central 上的 `io.github.karboom:filaments-java`，同一版本在 git 上打 `v<版本号>` 标签。

分类沿用 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的语义，标题用中文：`不兼容变更` / `新增` / `变更` / `修复`。
每条尽量写清「为什么改」而不是复述 diff——升级时判断影响面的成本，比读代码高得多。

## [2.2.0] - 2026-11-24

### 不兼容变更

- **配置的可变模型改写**：`FilamentsConfig` 去掉 Lombok `@With`，7 项配置回到 public 可写字段，改配置从「`withXxx()` 拿新副本」变成「`clone()` → 赋值 → 交给**新建**的实例」；安全性由快照约定兜住——`Filaments` 构造期 `clone` 传入的 config，之后调用方怎么改自己手里那份都影响不到已建好的实例。

  ```java
  // 2.1.x
  var cfg = new FilamentsConfig().withPageSize(7L);
  // 2.2.0
  var cfg = new FilamentsConfig().clone();
  cfg.pageSize = 7L;
  ```

- **`Filaments.config` 由 `public final` 降为 `protected final`**：外部读不到实例的配置了。留 public 等于给子类一条「顺手原地改」的路，而构造期已把命名结果物化成列索引与 JSON 字段集，原地改会让一半按旧名查索引、一半按新名拼 SQL，落到库里的是错数据而不是异常。需要读配置就在建实例前看自己那份。

- **默认命名改由 `mapper` 派生**：`FilamentsConfig.naming` 字段变成「显式绑定槽」（默认 `null`），生效值改由方法 `naming(String)` 读取，未绑定时按 `mapper` 的 `PropertyNamingStrategy` 现派生。内置 mapper 是 `SNAKE_CASE`，所以**默认行为与 2.1.x 等价**；改的是「换序列化风格时列名自动跟上」，以及 jsonb 点分通道的读写键同源由构造保证。迁移只有一处：`config.naming.apply(x)` → `config.naming(x)`。

  注意反向的坑：显式给 `naming` 赋值（如加表前缀）后，读写一致性转为自担——`#>>` 对不存在的键返回 NULL 而不是报错。

- **`lg` 表达式禁用 `=`**：带 `=` 的 token 直接抛 `FilamentsError`。原先 `lg=!(name=a,tags=b)` 这种内联值写法与「条件键上挂值」是两条并行通道，同一条件两种写法可以互相矛盾；现在值只有一种来历（挂在参数键上），`lg` 只做逻辑组合。

- **模型字段不接受 jOOQ `JSON` / `JSONB`**：构造期抛错。jsonb 列请用 POJO / `List` / `Map`，要裸 JSON 树用 `JsonNode`；`String` 只适用于 varchar 存 JSON 文本的列。原因：写侧 `JSON`/`JSONB` 是「原样绑定的值」，读侧 `rt` 投影拿到 `PGobject`、`SELECT *` 拿到 `JSONB`，取值阶梯两条都落不进「可赋值」，Jackson 也构造不出该类型——同一个字段两种读法结果不一致，不如构造期就拒。

- **取值型操作符拒绝 `null`**：`eq` / `ne` / `gt` / `ge` / `lt` / `le` / `like` / `not like` / `in` / `not in` / `between` / `not between` 遇 null 抛 `FilamentsError`，消息指到第几个值。原先 null 会绑上去渲染成 `f = ?` + NULL，三值逻辑下谓词恒不真：`eq` 恒 0 行，`notIn` 连「排除」都变成 0 行，调用方只看得到空结果集，排查方向被引到存量数据上。出路：判空用 `isNull` / `isNotNull`（全表唯一「值不参与」的地方）；要匹配文本 `'null'` 就显式传字符串；条件不生效就别把这个键放进 map。URL 入口传不进真 null，这条只约束 map / JSON 入参与手工构造的 `FieldCondition`。

### 新增

- `FilamentsConfig.namingFrom(ObjectMapper)` 与 `naming(String)`：从 Jackson 键策略派生「模型字段名 → 列名 / jsonb 键」的改写函数，`snake` / `camel` / `lower` / `asIs` 及自组合规则保留为显式绑定备选。
- 文档站（Astro + Starlight，`docs/`）：快速开始、查询语法、命名与配置、校验与空值、进阶（自定义 Filaments）、FAQ。

### 变更

- `rt` 显式投影不再给 JSON 列补 `JSONB` 类型。补类型不改渲染出的 SQL，只改交给取值阶梯的中间形态，而 JSON 字段本来就按 `String.class` 收原文再交给 Jackson——形态收敛统一做在 `doQuery` 的取值阶梯一处（驱动裸类型 / 元数据形态 / varchar 文本三种都在这里归一），投影侧不再按 `jsonFields` 二次判定（那个集合与「该列是否 JSON 语义」并不严格重合）。`rt` 与 `SELECT *` 的结果自此一致。
- `Filaments` 内部列名归一化统一走 `config.naming(...)`，且 `rt` 通道只归一化一次。

### 修复

- `rt` 指定列 + 非幂等命名规则（如表前缀 `s -> "t_" + snake(s)`）下，列名被归一化两次，拼出 `t_t_name` 这类双重前缀列，直接报 `column does not exist`。`rt` 分支原先复用 `columnRef`（内部还会再跑一次 naming），现改为拿到已归一化的列名后直接 `field(name(col))`。

## [2.1.1] - 2026-11-21

### 新增

- CI：`.github/workflows/ci.yml`——PG 16 service（`trust` 免密）+ JDK 24 + `./gradlew check`，失败时上传测试 / checkstyle / jacoco 报告。
- 模型类型构造期校验：沿父类链解析 `extends Filaments<YourModel>` 的泛型实参，raw 写法（不带类型实参、或类型实参是类型变量）当场抛错，不留到第一次用到 `dataClass` 才炸。
- 分页参数解耦：`p` 与 `pc` 各自独立可用，缺的那个取 `config.page` / `config.pageSize` 默认；**两个键都不给时不分页**，`get` 因此仍是全量取数入口，要「永远分页」用 `getByPages`。
- `pg` 改为「存在即开启」：只看键在不在，值不参与判断（`pg=0` 也算总数），不想要 count 就别带这个键。
- 聚合查询缺目标即抛错：`target` 与 `group` 同时为空时拒绝，否则 `buildBase` 会兜底成 `select *`，对 LLM 生成的调用（漏传 / 拼错 target）等于放开一条无上限的全表整行扫描。
- `raw` 只接受 jOOQ `Condition` 实例，其余形态（字符串 SQL 片段等）抛错而不是丢弃——丢弃就是 WHERE 少一个条件，结果集静默变大。
- `FilamentsConfig` 改为不可变（final 字段 + `withXxx`）。〔2.2.0 改回可写字段 + `clone()` 快照，安全性由构造期收副本保证〕

### 修复

- 模型字段扫描不再吃 `static` 字段（`public static final` 常量被当成列，凭空多出一个不存在的列名）。
- `lg` 与函数链的括号匹配：`substr(1,2`、`lower(` 这类未闭合写法原先静默截断、把参数吞掉，现在直接报错。
- `PageResult.total` 用 `Long`：PG 的 `count(*)` 是 `bigint`，`Integer` 在超过 21 亿行时溢出。

## [2.1.0] - 2026-11-16

### 新增

- `FilamentsConfig` 抽离为独立配置类：命名、分隔符、函数白名单、分页默认值、mapper 集中一处，经构造函数传入；不存在应用级全局配置对象。
- `lg` 别名语法：键写成 `别名::字段|函数链|操作符`，`lg` 里用短别名引用。解决两件事——带参数的函数链（`substr(1,2)`）里的逗号 / 括号是 `lg` 结构字符无法直接写，以及同一条件需要出现多次（不同值或不同操作符）时 Map 键折叠。
- 未指定 `rt` 时默认 `SELECT *`，列清单从首行结果集元数据取（`q.getSelect()` 对通配投影是空的）。
- 统一异常类型 `FilamentsError`（包装 Jackson 反序列化、反射赋值等数据相关失败时带上行号 / 列名 / 目标类型）。
- checkstyle（`config/checkstyle`，`maxWarnings = 0` / `maxErrors = 0`）与 jacoco 覆盖率门槛。
- 本地测试环境脚本：`script/base.sql`、`script/docker-compose.test.yml`、`script/start_test_env.sh`。

### 变更

- 核心重构：查询条件先解析成 `SelectSpec`（`spec` 包：`SelectSpec` / `FieldCondition` / `LogicNode` / `OrderSpec`）再驱动 jOOQ 构建，解析与拼装解耦。
- 校验入口改为 `initValidator()` 抽象方法，`Validator` 由子类构造时提供。

## [2.0.2] - 2026-10-28

### 变更

- 升级依赖（jOOQ / Jackson / yavi / hutool 及构建插件），README 同步。

## [2.0.1] - 2026-10-24

### 新增

- `updateByIds` 跳过主键列：批量更新时 SET 主键会撞唯一约束（且两边都归一化后再比对，`wechatId` / `wechat_id` 混写也能认出是主键）。

## [2.0.0] - 2026-06-12

### 变更

- 查询构建改由 spec 驱动（本仓库该版本号只出现在 `20940ab`，Maven Central 未收录 2.0.0，首个 2.x 正式版是 2.0.1）。

## [1.1.16] - 2026-06-10

### 变更

- 查询构建逻辑抽离，模型类型兼容性放宽。

## [1.1.10] - 2025-03-15

### 新增

- `Map` 类型字段按 jsonb 存取。

## [1.1.8] - 2025-03-11

### 修复

- PG json 解析问题（jsonb 文本形态与结构形态混用时的取值失败）。

## [1.1.7] - 2025-02-17

### 新增

- 查询条件命名风格转换（`snake` / `camel` / `lower` / `asIs` 可选），驼峰模型字段可映射下划线列。

## [1.1.6] - 2025-02-07

### 新增

- `OffsetDateTime` 支持（时区敏感的 `timestamptz` 列）。

## [1.1.5] - 2025-02-06

### 新增

- `ne`（不等于）操作符。

## [1.1.4] - 2025-02-05

### 新增

- PostgreSQL json 路径访问支持（`extra.xxx` 点分通道）。

### 变更

- 最低 Java 版本升级。

## [1.1.3] - 2025-01-17

### 变更

- 分页逻辑完善：`count(*)` 与行结果分离，`getByPages` 的总数不再受 limit / offset 影响。

## [1.0.0] - 2024-11-27

首个版本：建立在 jOOQ 之上的查询 DSL——`字段|函数|操作符=值` 形态的短字符串条件、`lg` 逻辑组合、CRUD 与分页、jsonb 读写。

[2.2.0]: https://github.com/karboom/filaments-java/compare/a2d527c...v2.2.0
[2.1.1]: https://github.com/karboom/filaments-java/compare/5406576...a2d527c
[2.1.0]: https://github.com/karboom/filaments-java/compare/6fb98b9...5406576
[2.0.2]: https://github.com/karboom/filaments-java/compare/5ea7e31...6fb98b9
[2.0.1]: https://github.com/karboom/filaments-java/compare/20940ab...5ea7e31
