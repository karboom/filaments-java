# Filaments Java

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java Version](https://img.shields.io/badge/java-22%2B-blue.svg)](https://www.oracle.com/java/technologies/javase-downloads.html)

一个轻量级的 Java ORM 库，提供了一组语法可以将查询字符串直接转换为 SQL 查询，并且提供了一组 NLP 友好的数据操作接口。

## 特性

- 🔄 将查询字符串自动转换为 SQL 查询
- 🔍 支持丰富的查询操作符和函数
- 📦 易于集成到现有项目中
- ⚡ 高性能的数据访问层
- 🛠️ 灵活的扩展机制

## 快速开始

### 安装

确保你的项目使用 Gradle 或 Maven 构建，然后添加依赖：

#### Gradle

```gradle
dependencies {
    implementation 'me.karboom.java:filaments:1.1.16'
}
```

#### Maven

```xml
<dependency>
    <groupId>me.karboom.java</groupId>
    <artifactId>filaments</artifactId>
    <version>1.1.16</version>
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
