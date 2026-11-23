---
title: 常见问题
description: 复杂 SQL、null 语义、DSLContext 首参数等高频疑问
---

## 要跑更复杂的 SQL？

在数据库里建视图；用 `aggregation` 的 `sub`；或在子类里自建方法直接用注入的 `DSLContext`，
见[扩展用法](extending.md)。

## 字段传 null 为什么没触发必填校验？

见[校验与空值](../reference/validation.md)：根字段为 null 时约束整条跳过，这是部分更新能工作的前提。
必填请用列上的 `NOT NULL`。

## 查询条件传 null 为什么直接抛错？

见[值的形态](../reference/query-syntax.md)：null 绑进 `= ?` 在 SQL 里恒不真，静默返回 0 行比报错更难查；
库不猜意图。判空请用 `isNull` / `isNotNull`，要匹配文本 `'null'` 就传字符串，
条件不生效就别把这个键放进 map。写侧（`create` / `updateByIds`）的 null 仍是 SQL NULL，与这里无关。

## 第一个参数为什么都是 `DSLContext`？

便于按业务选择连接（主库／从库）与事务上下文。库不持有连接、不管事务边界，
这两件事留给调用方，也方便在测试里注入临时连接。

## 支持 MySQL / SQL Server 吗？

行查询、分页、聚合这些通道是可移植的，但两处 PostgreSQL 专属：点分 jsonb 路径渲染成 `col #>> '{path}'`，
`intersect` 渲染成 `jsonb_path_exists(...)`。换 `sqlFuncList` 预设（`MYSQL_FUNCS` / `SQLSERVER_FUNCS`）
只换函数白名单，不会让这两条通道变得可用。详见[类型与 JSON 约定](../reference/naming-and-config.md)。

## 怎么确认库把我的条件拼成了什么？

两个办法：

- `SelectSpecBuilder#parse(query)` 拿到 `SelectSpec`，`LogicNode#toTreeString()` 打印条件树；
- `buildSelect(db, query, null)` 拿到 `SelectQuery` 后按 jOOQ 的方式 `toString()` / 渲染 SQL 查看。

## 贡献与许可

源码与 issue 都在 [karboom/filaments-java](https://github.com/karboom/filaments-java)，
欢迎提交 Issue 和 Pull Request。Licensed under the [Apache License, Version 2.0](https://github.com/karboom/filaments-java/blob/main/LICENSE)。
