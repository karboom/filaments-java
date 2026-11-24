---
title: 快速开始
description: 依赖坐标、前置要求与第一个 Filaments 实例
---

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Java | 22+ |
| 数据库 | 仅支持 PostgreSQL |
| JDBC 连接串 | 必须带 `stringtype=unspecified` |
| 运行时依赖 | jOOQ 3.21.x、Jackson 3.x、yavi 0.17.x |

::: warning 连接串必须带 `stringtype=unspecified`
JSON 字段以 JSON 文本绑定，缺了它驱动按 VARCHAR 传参，PG 会拒 `jsonb 列 = varchar 表达式`。
例如 `jdbc:postgresql://host:5432/db?stringtype=unspecified`。
:::

## 添加依赖

```groovy
implementation 'io.github.karboom:filaments-java:2.2.0'
```

```xml
<dependency>
    <groupId>io.github.karboom</groupId>
    <artifactId>filaments-java</artifactId>
    <version>2.2.0</version>
</dependency>
```

## 一个实例对应一张表

`Filaments` 是抽象类，模型类型取自 `extends Filaments<User>` 的泛型实参；
`initValidator()` 是唯一的抽象方法（yavi 校验入口，无校验需求时返回空 builder）。

```java
var userFilament = new Filaments<User>("user") {
    @Override
    public ValidatorBuilder<User> initValidator() {
        return new ValidatorBuilder<>();
    }
};
```

需要定制命名、mapper 或分页默认值时，把配置好的 `FilamentsConfig` 作为第二个参数传入，
详见[命名与配置](../reference/naming-and-config.md)。

## 查询

所有读入口的第一个参数都是 `DSLContext`，便于按业务选择连接（主库／从库）与事务上下文。

```java
// 条件是一段字符串：extra.age 取 jsonb 路径，substr 是函数链，ge 是操作符
var users = userFilament.get(db, new HashMap<>() {{
    put("age|in", List.of(22, 23));
    put("name|lower|like", "an%");
    put("od", "-create_time");
    put("rt", "id,name");
}});
```

## 分页

```java
// 返回 PageResult（list + total）；total 仅在传入 pg 时计算
var page = userFilament.getByPages(db, new HashMap<>() {{
    put("name", "x");
    put("pg", 1);
}});

System.out.println(page.total);  // Long：PG count(*) 是 bigint
System.out.println(page.list);
```

::: info
`get()` 在 `p` / `pc` 两个键都不给时不分页（全量取数入口）；`getByPages()` 会给两个键都补默认值，
要"永远分页"就用它。
:::

## 写入与更新

```java
// 批量创建：先全量校验，任一行不过则整批不写
userFilament.create(db, List.of(u1, u2));

// patch 语义：data 里为 null 的字段不进 SET
userFilament.updateByIds(db, List.of("1", "2"), patch);

userFilament.deleteByIds(db, List.of("3"));
```

## 接口一览

| 方法 | 作用 |
| --- | --- |
| `create(db, data)` / `create(db, List)` | 写入，逐行执行校验器 |
| `updateByIds(db, ids, data)` | 按 id 批量更新，null 字段不进 SET |
| `deleteByIds(db, ids)` | 按 id 批量删除 |
| `get(db, query)` | 行查询（全量，条件同查询字符串） |
| `getByPages(db, query)` | 分页查询，返回 `PageResult<T>` |
| `getByIds(db, ids)` | 按 id 取行，可选 `lock` |
| `aggregation(db, target, query, group, sub)` | 聚合，返回 `List<Map<String, Object>>` |

## 接下来

- [查询语法](../reference/query-syntax.md)：保留关键字、字段表达式、操作符与值的形态
- [扩展用法](extending.md)：子查询、原生 SQL、自建入口
