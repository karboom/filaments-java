<h1 align="center">Filaments Java</h1>

<p align="center">
  <i>A lightweight Java ORM designed for natural language and vibe coding</i>
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

<p align="center">
  <a href="README.en.md">English</a> · <a href="README.md">简体中文</a>
</p>

Filaments is a lightweight Java ORM that turns query strings into SQL. Its interface is **made for natural language**: a condition is a short string in the form `field|function|operator=value`, which maps almost one-to-one to how humans describe requirements. That makes it an ideal data-layer target for **AI / LLM-generated code (vibe coding)**.

Built on top of [jOOQ](https://www.jooq.org/), it keeps full control over connections and transactions while removing all the boilerplate.

## Features

- 🔄 Query strings compiled automatically into SQL
- 🔍 Rich set of operators and SQL functions
- 🗣️ Natural-language friendly: the query DSL is plain strings — no complex builder API for LLMs to memorize
- 🤖 Vibe-coding ready: uniform method signatures + convention-based mapping let an AI ship a data access layer in one conversation
- 📦 Easy to drop into existing projects
- ⚡ High-performance data access
- 🛠️ Flexible extension mechanism

## Why it plays so well with natural language / vibe coding

Asking an LLM to write queries with a traditional ORM means it has to memorize a large surface of builder APIs, types and fluent calls — a single typo breaks compilation. Filaments collapses all of that into **one highly regular, natural-sounding string syntax**:

1. **Near-zero translation cost from intent to query**
   "Find Beijing users older than 18, ordered by sign-up time descending" maps directly to:

   ```java
   filaments.pages(db, new HashMap<>() {{
       put("age|gt", 18);
       put("city", "Beijing");
       put("od", "-create_time");
   }});
   ```

   The `field|operator=value` pattern is isomorphic to how both Chinese and English state conditions (field → operator → value). Patterned short strings are exactly what LLMs generate most reliably — far fewer errors than fluent APIs.

2. **Parameters are pure data (Map / String), not code**
   Conditions can be emitted by an LLM as JSON and passed in directly — a perfect fit for Function Calling / tool arguments — and are easy to validate and replay server-side. Complex logic trees are expressed with `lg` expressions (e.g. `!(a=1,b=2)`), with `toTreeString()` for a visual self-check after generation.

3. **Strong conventions, zero configuration**
   camelCase fields map to snake_case columns automatically, JSON columns are (de)serialized transparently, `p/pc/pg` handle pagination, `rt` selects the return fields. The LLM never has to generate mapping config or boilerplate — the naming convention *is* the prompt.

4. **Tiny API surface, one conversation to master it**
   The core entry points are just `create / updateByIds / deleteByIds / get / pages / getByIds / aggregation`, all taking `DSLContext` as the first argument. A few few-shot examples cover the entire library, so generated code stays consistent and predictable.

5. **Clear security boundary**
   All values go through placeholder binding; field names, sort keys and group-by fields are validated against an identifier whitelist, function chains only accept functions from `FilamentsConfig.sqlFuncList` with numeric literal arguments, and JSON path segments are validated per-segment and escaped. Invalid input throws instead of reaching the SQL text, so AI-generated queries don't introduce concatenated-SQL injection risks — a solid foundation for "letting AI drive data access".

## Quick start

### Installation

Add the dependency with Gradle or Maven:

#### Gradle

```gradle
dependencies {
    implementation 'io.github.karboom:filaments-java:2.0.2'
}
```

#### Maven

```xml
<dependency>
    <groupId>io.github.karboom</groupId>
    <artifactId>filaments-java</artifactId>
    <version>2.0.2</version>
</dependency>
```

### Basic usage

```java
// create a Filaments instance
var userFilament = new Filaments<>(User.class);

// query with the query-string DSL
var users = userFilament.get(db, new HashMap<>() {{
    put("age|in", List.of(22, 23));
}});

// paginated query
var page = userFilament.pages(db, new HashMap(){{
    put("name", "x");
}});
```

## Query-string syntax

### Reserved keywords

| Key | Purpose                          | Default | Example                     | SQL                                                      |
|-----|----------------------------------|---------|-----------------------------|----------------------------------------------------------|
| rt  | select fields                    | *       | ?rt=name,age                | `select name, age`                                       |
| p   | page number                      | 1       | ?p=3                        | `limit 20 offset 40`                                     |
| pc  | page size                        | 20      | ?pc=18                      | `limit 18 offset 0`                                      |
| od  | ordering (`-` = desc)            | null    | ?od=id,-height              | `order by id asc, height desc`                           |
| pg  | compute total count (in `pages`) | 0       | ?pg=1                       | `count(*)` alongside `limit ? offset ?`                  |

### Field expressions

#### Format

```
field|function(args)...|operator=value
```

> Constraints: field names must be valid SQL identifiers (dotted paths like `data.type` are checked segment by segment); functions must be in the `FilamentsConfig.sqlFuncList` whitelist (default: built-in `FilamentsConfig.PG_FUNCS`; `MYSQL_FUNCS` / `SQLSERVER_FUNCS` presets are provided for wholesale replacement, and you can freely extend it), and function arguments must be numeric literals. Violations throw instead of being rendered into SQL.

> Naming: condition keys, `rt`, `od`, group fields and the `pk` column are all normalized as camelCase → lowercase snake_case (`wechatId` → `wechat_id`, and consecutive capitals are folded too: `userID` → `user_id`), so either spelling addresses the same column. In a dotted jsonb path **segment 0** follows the column rule and the remaining segments follow the JSON-key rule, so `extra.totalCount` and `extra.total_count` address the same key. Both rules are configurable, see [Naming and configuration](#naming-and-configuration).

#### Examples

| Query string                                       | SQL                                                            |
|----------------------------------------------------|----------------------------------------------------------------|
| ?type=dog                                          | `where type = 'dog'`                                           |
| ?type&#124;in=dog,cat                              | `where type in ('dog', 'cat')`                                 |
| ?type&#124;substr(1,2)&#124;in=dog,cat             | `where substr(type, 1, 2) in ('dog', 'cat')`                   |
| ?type&#124;lower&#124;substr(1,2)&#124;in=dog,cat  | `where substr(lower(type), 1, 2) in ('dog', 'cat')`            |
| ?data.type=dog                                     | `where data #>> '{type}' = 'dog'` (PG JSON path)               |
| ?data[0].type=cat                                  | `where data #>> '{0,type}' = 'cat'`                            |
| ?data[0]=dog                                       | `where data #>> '{0}' = 'dog'`                                 |

#### Condition Aliases

In an `lg` expression the characters `(` `)` `!` `,` and `=` are all reserved, but a func-chain key such as `extra.age|substr(1,2)|ge` contains parentheses and commas; and since Map keys are unique, the same expression used twice (different operator or value) would overwrite itself.

So a condition key may carry an alias prefix: `alias::field|funcChain|operator`, and `lg` then references the short alias while the value stays in the parameter map.

```java
var users = filaments.get(db, new HashMap<>() {{
    put("lowAge::extra.age|substr(1,2)|ge", "5");   // substr(extra #>> '{age}', 1, 2) >= 5
    put("highAge::extra.age|substr(3,4)|le", "10"); // same field, different func chain
    put("dup1::name|lower", "ann");                 // same expression appearing twice (implicit EQ)
    put("dup2::name|lower", "bob");
    put("lg", "(!(lowAge,highAge),dup1,dup2)");     // (lowAge OR highAge) AND dup1 AND dup2
}});
```

> Constraints: the whole `lg` expression must be wrapped in one pair of parentheses; an alias must be a valid identifier (letter/underscore first, then letters, digits or underscores) and must be unique; referencing a missing or invalid alias throws. Aliases are only used for addressing parameters and never reach the generated SQL.

### Operators

| Query string             | Alias | SQL                                  |
|--------------------------|-------|--------------------------------------|
| ?id&#124;gt=1            |       | `where id > 1`                       |
| ?id&#124;ge=1            |       | `where id >= 1`                      |
| ?id&#124;lt=1            |       | `where id < 1`                       |
| ?id&#124;le=1            |       | `where id <= 1`                      |
| ?id&#124;in=1            |       | `where id in (1)`                    |
| ?id&#124;not_in=1        | nin   | `where id not in (1)`                |
| ?id&#124;between=1,2     | bt    | `where id between 1 and 2`           |
| ?id&#124;not_between=1,2 | nbt   | `where id not between 1 and 2`       |
| ?id&#124;like=k%         | lk    | `where id like 'k%'`                 |
| ?id&#124;not_like=k%     | nlk   | `where id not like 'k%'`             |
| ?id&#124;is_null=1       | nl    | `where id is null`                   |
| ?id&#124;is_not_null=1   | nnl   | `where id is not null`               |

### Naming and configuration

Everything tunable now lives on `FilamentsConfig` — the old `Filaments` static compatibility shims are all gone.

How a model field name becomes a DB column name, and how it becomes a JSON key, is controlled by **one naming function** (a system is assumed to use one consistent style). The rule is a plain function — the library ships `snake` / `camel` / `lower` / `asIs` as candidates, and you can swap or compose your own:

```java
// FilamentsConfig.snake / camel / lower / asIs   (asIs = use the declared name verbatim)

// config is a plain object: build one and pass it to instances; passing the same
// object to several instances is how you do "application-wide" settings
// (the FilamentsConfig.defaults static is gone; instances built without a config
//  each start from built-in defaults)
var config = new FilamentsConfig();
config.naming = FilamentsConfig::camel;
// composing a custom rule, e.g. a uniform table prefix:
// config.naming = s -> "t_" + FilamentsConfig.snake(s);

var filament = new Filaments<User>("filaments_camel", config) {
    @Override
    public ValidatorBuilder<User> initValidator() { ... }
};
```

| Function | `wechatId` | `userID` | `wechat_id` | Jackson strategy (JSON side) |
| --- | --- | --- | --- | --- |
| `snake` (default) | `wechat_id` | `user_id` | `wechat_id` | `SNAKE_CASE` |
| `camel` | `wechatId` | `userId` | `wechatId` | `LOWER_CAMEL_CASE` |
| `asIs` | `wechatId` | `userID` | `wechat_id` | verbatim |
| `lower` | `wechatid` | `userid` | `wechat_id` | lowercase |

- `naming` drives condition keys, `rt`, `od`, group fields, `pk`, insert/update column names, aggregation aliases, and jsonb query path segments (`xxx` in `extra.xxx`).
- Aggregation aliases derive from the column name: `count(wechatId)` → `countWechatId`, `countDistinct(id)` → `countDistinctId`.
- Changing `naming` does not migrate existing columns or jsonb rows; use a per-instance config or rewrite the data.
- Serialization goes through exactly one instance, `config.mapper` (built-in SNAKE style by default). The library ships no mapper factory: build your own via `JsonMapper.builder().propertyNamingStrategy(...).disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).build()` — keep the disable or offset-bearing times silently shift to the local zone — and pair it with `naming` (defaults are both snake and match out of the box).

Everything else tunable lives on the same config object (share one instance or build one per table):

| Option | Default | Purpose |
| --- | --- | --- |
| `nameSplitter` | `"\|"` | separator of `field\|function\|operator` in query keys |
| `aliasSplitter` | `"::"` | condition-alias separator (`alias::field\|function\|operator`), never reaches SQL |
| `sqlFuncList` | `PG_FUNCS` (also `MYSQL_FUNCS` / `SQLSERVER_FUNCS`) | function-chain and aggregate whitelist, swap preset or extend |
| `page` / `pageSize` | 1 / 20 | paging used by `pages()` when `p` / `pc` are absent |

### Types and JSON

Values are not mapped through hand-written setters. The classification happens once, at construction: fields declared as `List` / `Set` / `Map` / POJO become "JSON fields" — serialized on write, parsed back per value on read; everything else is bound and mapped with scalar semantics. Reads map columns straight into the model (no whole-row JSON round trip); only JSON-field values touch Jackson. Declaring a model field as `Object` throws at construction — use `Map<String,Object>` or a concrete POJO for jsonb.

| Model field type | Write | Read |
| --- | --- | --- |
| `String` / numbers / `BigDecimal` / `java.time.*` / `boolean` / `byte[]` / `UUID` | bound as-is (not JSON-wrapped) | column-level mapping: assignable values set directly, others converted per value (Jackson) or via jOOQ converters (time) |
| `java.util.Date` / `java.sql.*` time fields | bound as-is | read fails loudly: timestamptz offset semantics cannot land losslessly on these types — switch to `java.time` |
| enum | stored as `name()` (e.g. `A`) | `A` → enum |
| `List` / `Set` / `Map` / nested object | serialized as JSON text (decided by declared type, not runtime value) | projections carry JSONB typing (via `rt`) or column metadata, so reads get object/array nodes directly |

> JSON-ness is decided entirely at construction: no runtime string sniffing remains, so a text column whose value happens to start with `{` or `[` (e.g. `{"a":1}`) is always read back verbatim instead of failing the whole query.

> Pre-existing camelCase jsonb keys (written by another system) are silently ignored (read as null) under the default `SNAKE`; give that table an instance with `naming = FilamentsConfig::asIs` (or `::camel`) — note naming now also renames columns, so this fits databases that are uniformly camelCase. Fields annotated with `@JsonProperty("originalKey")` keep their exact key name (Jackson 3 semantics).

## Advanced usage

### Sub-queries

For more complex needs, pass a pre-built sub-query via the `sub` argument:

```java
var subQuery = dsl.selectQuery();
var users = userFilament.get(db, new HashMap<>() {{
    put("role", "admin");
}}, subQuery);
```

### Custom extensions

Extend the `Filaments` class with your own methods:

```java
public class UserFilaments extends Filaments<User> {
    public UserFilaments() {
        super("user");
    }

    public List<Record> getByOrgId(DSLContext db, Long orgId) {
        // drop down to raw jOOQ whenever you need it
        return db.select().from(table("user")).where(...).fetch();
    }
}
```

## FAQ

### How do I run more complex SQL?

1. Define a database view and point a Filaments at it
2. Pass a hand-built sub-query via the `sub` argument
3. Extend `Filaments` and use the underlying `DSLContext` directly

### Why is the first argument always `DSLContext`?

1. Pick the connection per call (primary vs. replica)
2. Full control over the transaction context

## Contributing

Issues and Pull Requests are welcome!

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
