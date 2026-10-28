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
   filaments.pages(db, new HashMap<>() {{
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
   核心入口只有 `create / updateByIds / deleteByIds / get / pages / getByIds / aggregation` 几个方法，第一个参数统一是 `DSLContext`。AI 只需少量示例（few-shot）即可覆盖整个库的用法，生成的代码风格高度一致、可预测。

5. **安全边界清晰**
   所有值走占位符绑定，注入面收敛在字段名/函数白名单内，LLM 生成的查询不会引入拼接式 SQL 注入风险，适合作为"让 AI 直接操作数据查询"的落地层。

## 快速开始

### 安装

确保你的项目使用 Gradle 或 Maven 构建，然后添加依赖：

#### Gradle

```gradle
dependencies {
    implementation 'io.github.karboom:filaments-java:2.0.1'
}
```

#### Maven

```xml
<dependency>
    <groupId>io.github.karboom</groupId>
    <artifactId>filaments-java</artifactId>
    <version>2.0.1</version>
</dependency>
```

### 基本用法

```java
// 创建 Filaments 实例
var userFilament = new Filaments<>(User.class);

// 使用查询字符串进行查询
var users = userFilament.get(db, new HashMap<>() {{
    put("age|in", List.of(22, 23));
}});

// 分页查询
var page = userFilament.pages(db, new HashMap(){{
    put("name", "x");
}});
```

## 查询字符串语法

### 保留关键字

| 字段名称 | 功能                    | 默认值  | 查询字符串                                             | 对应SQL                                                             |
|------|-----------------------|------|---------------------------------------------------|-------------------------------------------------------------------|
| rt   | 指定返回字段                | *    | ?rt=name,age                                      | ```select `name`, `age` ```                                       |
| p    | 指定当前页码                | 1    | ?p=3                                              | ```limit 20 offset 40```                                          |
| pc   | 指定页内行数                | 20   | ?pc=18                                            | ```limit 18 offset 0```                                           |
| od   | 指定排序规则                | Null | ?id,-height                                       | ``` order by `id` asc, `height` desc```                           |
| pg   | 是否需要计算分页(仅分页函数生效)     | 0    | ?pg=1                                             | ``` limit ? offset ? ```                                          |

### 字段表达式

#### 格式
```
字段名|函数(参数)...|操作符=数值
```

#### 示例

| 查询字符串                                             | 对应SQL                                                       |
|---------------------------------------------------|-------------------------------------------------------------|
| ?type=dog                                         | ``` where `type` = 'dog' ```                                |
| ?type&#124;in=dog,cat                             | ``` where `type` in ( 'dog', 'cat') ```                     |
| ?type&#124;substr(1,2)&#124;in=dog,cat            | ``` where substr(`type`, 1, 2) in ( 'dog', 'cat')```        |
| ?type&#124;lower&#124;substr(1,2)&#124;in=dog,cat | ``` where substr(lower(`type`), 1, 2) in ( 'dog', 'cat')``` |
| ?data.type=dog                                    | ``` where `data`->'$.type' = 'dog' ```                      |
| ?data[0].type=cat                                 | ``` where `data`->'$[0].type' = 'cat' ```                   |
| ?data[0]=dog                                      | ``` where `data`->'$[0]' = 'dog' ```                        |

### 操作符

| 查询字符串                    | 别名  | 对应SQL                                   |
|--------------------------|-----|-----------------------------------------|
| ?id&#124;gt=1            |     | ```  where `id` > 1 ```                 | 
| ?id&#124;ge=1            |     | ```  where `id` >= 1 ```                | 
| ?id&#124;lt=1            |     | ```  where `id` < 1 ```                 | 
| ?id&#124;le=1            |     | ```  where `id` <= 1 ```                | 
| ?id&#124;in=1            |     | ```  where `id` in (1) ```              | 
| ?id&#124;not_in=1        | nin | ```  where `id` not in (1) ```          | 
| ?id&#124;between=1,2     | bt  | ```  where `id` between 1 and 2 ```     | 
| ?id&#124;not_between=1,2 | nbt | ```  where `id` not between 1 and 2 ``` | 
| ?id&#124;like=k%         | lk  | ```  where `id` like 'k%' ```           | 
| ?id&#124;not_like=k%     | nlk | ```  where `id` not like 'k%' ```       |
| ?id&#124;is_null=1       | nl  | ```  where `id` is null ```             |
| ?id&#124;is_not_null=1   | nnl | ```  where `id` is not null ```         |

## 高级用法

### 子查询

对于复杂的查询需求，可以通过 `sub` 参数传入子查询：

```java
var subQuery =  dsl.selectQuery();
var users = userFilament.get(db, new HashMap<>() {{
    put("role", "admin");
}}, subQuery);
```

### 自定义扩展

继承 `Filaments` 类，扩展自定义方法：

```java
public class UserFilaments extends Filaments<User> {
    public UserFilaments() {
        super(User.class);
    }
    
    public List<Record> getByOrgId(DSLContext db, Long orgId) {
        return db.raw();
    }
}
```

## 常见问题

### 如何支持更复杂的 SQL

1. 在数据库中编写视图
2. 通过 `sub` 参数传入构造的子查询
3. 继承 `Filaments` 类，扩展自定义方法，通过 `db.raw()` 直接编写语句

### 为什么第一个参数都是 DSLContext

1. 根据业务需求灵活选择数据库连接（主库或从库）
2. 灵活选择事务上下文

## 贡献

欢迎提交 Issue 和 Pull Request！

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
