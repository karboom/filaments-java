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
   All values go through placeholder binding; the injection surface is confined to the field-name / function whitelist, so AI-generated queries don't introduce concatenated-SQL injection risks — a solid foundation for "letting AI drive data access".

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
