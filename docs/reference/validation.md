---
title: 校验与空值
description: yavi 校验的执行时机、null 根字段的跳过规则，以及为什么必填应该交给数据库
---

`create`（逐行建校验器）与 `updateByIds`（一次）在写库前执行 `initValidator()`，
任一条不过即抛 `FilamentsError`（yavi 第一条违规文案）；约束名与模型字段名对不上抛
`模型 X 没有字段 y`。批量先全量校验再一次性 `execute`，任一行不过则整批不写。
传入的 builder 可跨实例／跨行共享，库不会改动它。

## 根字段为 null 时约束跳过

**根字段为 `null` 时，该字段所在的整条约束跳过**——这是部分更新（null = 不改写）与嵌套路径约束
（父对象可能整个缺失）能工作的前提，代价是 `null` 无法表达"必填"：

| 数据形态 | 是否校验 |
| --- | --- |
| `name = null`，约束 `notBlank` | 跳过（不报错） |
| `name = ""`，约束 `notBlank` | 抛 `FilamentsError` |
| 约束 `address.city`，`address = null` | 跳过（父对象缺失） |
| 约束 `address.city`，`address != null`、`city = null` | 抛 `FilamentsError` |
| `updateByIds(ids, patch)` | 同上；`patch` 里为 null 的字段不进 SET，必填字段无法经 update 置空 |

::: info
读侧的 null 是另一回事：条件值绑 `null` 一律抛错，不折算成任何语义，
见[值的形态](query-syntax.md)。写侧（`create` / `updateByIds`）的 null 仍是 SQL NULL。
:::

## 必填交给数据库

列上加 `NOT NULL`：null 字段渲染成 `insert … values (default, …)` 由 PG 拒绝，
一条 INSERT 是原子的（批量中某条违例则 0 行写入）。三个漏网处：

| 情况 | 结果 | 对策 |
| --- | --- | --- |
| 列是 `NOT NULL DEFAULT 'A'` | null 被默认值吃掉，看不出调用方没传 | 要求显式给值就别设默认值，或在 `before` 钩子里自查 |
| 空串 `''` | `NOT NULL` 不拦 | 用 yavi 的 `notBlank`（值非 null，约束会生效） |
| jsonb 内部键（如 `address.city` 必填） | DB 只看整列是否为 null | 在 `before` 钩子里补 |

::: warning
约束冲突抛的是 jOOQ 的 `IntegrityConstraintViolationException`（含表名、列名与 SQL 原文），
不是 `FilamentsError`；需要统一错误出口、或不想把物理表结构透给上层（异常文本会回喂给 LLM）时，
在 service 层捕获。
:::

## 校验器写法

`initValidator()` 返回 yavi 的 `ValidatorBuilder<T>`，约束通过 getter 或字段引用定位：

```java
@Override
public ValidatorBuilder<User> initValidator() {
    var b = new ValidatorBuilder<User>();
    b._string(User::getName, "name", c -> c.notBlank());
    b._string(u -> u.address.city, "address.city", c -> c.notBlank());
    return b;
}
```

无校验需求时返回空 builder 即可，`Filaments` 不会拒绝。
