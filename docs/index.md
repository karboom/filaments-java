---
layout: home

hero:
  name: "Filaments Java"
  text: "为自然语言与 vibe coding 而生的轻量 Java ORM"
  tagline: "构建在 jOOQ 之上：查询条件是 <code>字段|函数|操作符=值</code> 这样的短字符串，与人类描述需求的语序同构——人好写，LLM 也好生成。"
  actions:
    - theme: brand
      text: 快速开始
      link: /guide/getting-started.html
    - theme: alt
      text: 查询语法
      link: /reference/query-syntax.html
    - theme: alt
      text: GitHub
      link: https://github.com/karboom/filaments-java

features:
  - title: 零翻译成本
    icon: 🚀
    details: '"年龄大于 18 的北京用户，按注册时间倒序" 就是 <code>age|gt=18</code>、<code>city=北京</code>、<code>od=-create_time</code>。<code>字段 → 操作符 → 值</code> 与中英文语序同构，比链式 builder API 出错率低得多。'
  - title: 参数是数据不是代码
    icon: 🧩
    details: '条件以 <code>Map</code> / JSON 传入，天然适配 Function Calling，也便于服务端校验与回放。复杂逻辑用 <code>lg</code> 表达式（<code>!(a,b)</code>），<code>LogicNode#toTreeString()</code> 打印出来即可自检。'
  - title: 接口小、约定强
    icon: ⚙️
    details: '入口只有 <code>create / updateByIds / deleteByIds / get / getByPages / getByIds / aggregation</code>，第一参数统一 <code>DSLContext</code>。驼峰自动转下划线、JSON 列自动序列化、<code>p/pc/pg</code> 分页，不需要映射配置与样板代码。'
  - title: 安全边界清晰
    icon: 🔒
    details: '值全走占位符绑定；字段名/排序键/分组字段逐段过标识符白名单，函数只认 <code>FilamentsConfig.sqlFuncList</code> 且参数限数字字面量；非法输入抛错而不是拼进 SQL。'
---

## 三十秒上手

```java
// 一个实例对应一张表：Filaments 是抽象类，模型类型取自 extends Filaments<User> 的泛型实参
var userFilament = new Filaments<User>("user") {
    @Override
    public ValidatorBuilder<User> initValidator() {
        return new ValidatorBuilder<>();
    }
};

// 年龄是 22 或 23 的用户
var users = userFilament.get(db, new HashMap<>() {{
    put("age|in", List.of(22, 23));
}});
```

## 下一步

- [快速开始](guide/getting-started.md)：依赖坐标、Java/PG 前置要求、第一个实例
- [查询语法](reference/query-syntax.md)：保留关键字、字段表达式、操作符、值的形态
- [命名与配置](reference/naming-and-config.md)：FilamentsConfig、naming/mapper 派生、类型与 JSON 约定
- [校验与空值](reference/validation.md)：yavi 校验时机、null 的三条出路、必填交给数据库
