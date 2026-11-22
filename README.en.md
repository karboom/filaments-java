<h1 align="center">Filaments Java</h1>

<p align="center">
  <i>A lightweight Java ORM built for natural language and vibe coding</i>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.karboom/filaments-java"><img src="https://img.shields.io/maven-central/v/io.github.karboom/filaments-java?label=Maven%20Central&logo=apachemaven&logoColor=white" alt="Maven Central"></a>
  <a href="https://javadoc.io/doc/io.github.karboom/filaments-java"><img src="https://img.shields.io/badge/javadoc-available-blueviolet?logo=readthedocs&logoColor=white" alt="Javadoc"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue?logo=apache&logoColor=white" alt="Apache-2.0 License"></a>
  <img src="https://img.shields.io/badge/Java-22%2B-orange?logo=openjdk&logoColor=white" alt="Java 22+">
  <a href="https://github.com/karboom/filaments-java"><img src="https://img.shields.io/github/stars/karboom/filaments-java?style=flat&logo=github&label=Stars" alt="Stars"></a>
  <a href="https://github.com/karboom/filaments-java/compare"><img src="https://img.shields.io/badge/PRs-welcome-brightgreen?logo=contribute&logoColor=white" alt="PRs Welcome"></a>
</p>

A lightweight ORM on top of jOOQ: a query condition is a short string shaped like `field|function|operator=value`, which mirrors how a human states the requirement — easy to write by hand, easy for an LLM to generate.

## Why it fits natural language / vibe coding

- **Near-zero translation cost**: "users in Beijing older than 18, newest first" is `age|gt=18`, `city=Beijing`, `od=-create_time`; the `field → operator → value` order matches English and Chinese, and misfires far less than chained builder APIs.
- **Parameters are data, not code**: conditions arrive as a `Map` / JSON, which suits Function Calling and tool arguments and is easy to validate and replay server-side; logic trees use `lg` expressions (`!(a,b)`), and `LogicNode#toTreeString()` prints them for a self-check.
- **Small surface, strong conventions**: only `create / updateByIds / deleteByIds / get / getByPages / getByIds / aggregation`, always taking `DSLContext` first; camelCase maps to snake_case columns, JSON columns serialize themselves, `p/pc/pg` paginate — no mapping config, no boilerplate.
- **Clear safety boundary**: every value is placeholder-bound; field names, sort keys and group fields pass an identifier whitelist per segment, functions must be in `FilamentsConfig.sqlFuncList` with numeric-literal arguments; invalid input throws instead of reaching the SQL text.

## Quick start

```gradle
implementation 'io.github.karboom:filaments-java:2.1.0'
```

```xml
<dependency><groupId>io.github.karboom</groupId><artifactId>filaments-java</artifactId><version>2.1.0</version></dependency>
```

Java 22+, PostgreSQL.

```java
// One instance per table: Filaments is abstract and takes the model type from
// `extends Filaments<User>`; initValidator is the only abstract method
// (yavi entry point — return an empty builder if you don't validate)
var userFilament = new Filaments<User>("user") {
    @Override
    public ValidatorBuilder<User> initValidator() {
        return new ValidatorBuilder<>();
    }
};

var users = userFilament.get(db, new HashMap<>() {{
    put("age|in", List.of(22, 23));
}});

// paginated: returns PageResult (list + total); total is only computed when pg is set
var page = userFilament.getByPages(db, new HashMap<>() {{
    put("name", "x");
    put("pg", 1);
}});
```

## Query-string syntax

### Reserved keywords

| Key | Purpose                          | Default | Example                     | SQL                                                      |
|-----|----------------------------------|---------|-----------------------------|----------------------------------------------------------|
| rt  | select fields (column names only — no jsonb paths/indexes) | *  | ?rt=name,age               | `select name, age`                                       |
| p   | page number                      | 1       | ?p=3                        | `limit 20 offset 40`                                     |
| pc  | page size                        | 20      | ?pc=18                      | `limit 18 offset 0`                                      |
| od  | ordering (`-` = desc)            | null    | ?od=id,-height              | `order by id asc, height desc`                           |
| pg  | compute total count — **presence is the switch, the value is ignored** (only in `getByPages`) | absent | ?pg=1 | `count(*)` unpaged; `limit ? offset ?` only on the rows  |

> `p` and `pc` work independently; whichever is missing falls back to `config.page` / `config.pageSize`, so `?p=3` alone is `limit 20 offset 40` and `?pc=18` alone is `limit 18 offset 0`. **With neither key there is no pagination at all** — `get` stays a full-scan entry point; use `getByPages` for "always paged" (it fills in both defaults).
>
> These five describe *row* results, so they only apply to `get` / `getByPages` / `getByIds`; `aggregation` consumes just the conditions (including `lg` / `raw`), so handing the same map to both is safe — pagination cannot truncate the count and row projections cannot leak into GROUP BY.
>
> `pg` is presence-based: any `pg` key computes the total, including `pg=0` and `pg=false`; leave the key out to skip the count.
>
> After splitting `rt` on commas each segment is a bare column name (not identifier-checked, no jsonb paths, no func chains): a column missing in the database surfaces as a DB error, a column missing in the model is silently skipped.

### Field expressions

```
field|function(args)...|operator=value
```

- Field names must be valid SQL identifiers (dotted paths like `data.type` are checked segment by segment); functions must be in `FilamentsConfig.sqlFuncList` (default `PG_FUNCS`, with `MYSQL_FUNCS` / `SQLSERVER_FUNCS` presets) and take numeric literals only. Violations throw.
- `|` separates segments of *one* key and is not "OR": `name|lower|like` is the single condition `lower(name) like ?`. Only a trailing segment that matches the operator table is an operator; the rest are func-chain entries. OR / AND are written with `lg` (`(a,b)`, `!(a,b)` — `!` only marks a group as OR-joined, it does not negate); negation exists only at the predicate level via `ne` / `notIn` / `notBetween` / `notLike` / `isNotNull`, there is no group-level NOT yet.
- Every name goes through `config.naming` (default camelCase → lowercase snake_case, `userID` → `user_id`), so either spelling addresses the same column. In a dotted jsonb path **segment 0** follows the column rule and the rest the JSON-key rule, so `extra.totalCount` and `extra.total_count` address the same key; `data[0].type` and `data.0.type` are equivalent (a bracket must contain a plain integer). See [Naming and configuration](#naming-and-configuration).

| Query string                                       | SQL                                                            |
|----------------------------------------------------|----------------------------------------------------------------|
| ?type=dog                                          | `where type = 'dog'`                                           |
| ?type&#124;in=dog,cat                              | `where type in ('dog', 'cat')`                                 |
| ?type&#124;substr(1,2)&#124;in=dog,cat             | `where substr(type, 1, 2) in ('dog', 'cat')`                   |
| ?type&#124;lower&#124;substr(1,2)&#124;in=dog,cat  | `where substr(lower(type), 1, 2) in ('dog', 'cat')`            |
| ?data.type=dog                                     | `where data #>> '{type}' = 'dog'` (PG JSON path)               |
| ?data[0].type=cat                                  | `where data #>> '{0,type}' = 'cat'`                            |
| ?data[0]=dog                                       | `where data #>> '{0}' = 'dog'`                                 |

#### Condition aliases

In `lg` the characters `(` `)` `!` `,` `=` are all reserved, while a func-chain key such as `extra.age|substr(1,2)|ge` carries parentheses and commas; and since Map keys are unique, reusing one expression (different operator or value) would overwrite it. So a key may take an `alias::field|funcChain|operator` prefix and `lg` references just the short alias, with the value still in the map:

```java
var users = filaments.get(db, new HashMap<>() {{
    put("lowAge::extra.age|substr(1,2)|ge", "5");   // substr(extra #>> '{age}', 1, 2) >= 5
    put("highAge::extra.age|substr(3,4)|le", "10"); // same field, different func chain
    put("dup1::name|lower", "ann");                 // same expression, twice (implicit EQ)
    put("dup2::name|lower", "bob");
    put("lg", "(!(lowAge,highAge),dup1,dup2)");     // (lowAge OR highAge) AND dup1 AND dup2
}});
```

> `lg` only composes parameter keys: a token is a condition key or an alias reference, values always stay in the query map — an inline `k=v` throws. Top-level bare tokens are valid (`a`, `a,(b,c)`; juxtaposed elements combine with AND), while parenthesis groups without any separator (`(a)(b)`) throw. An alias must be a valid identifier (letter/underscore first, then letters, digits or underscores) and unique, and referencing a missing alias throws. Aliases only address parameters and never reach the SQL.

### Operators

Operator tokens are exact and case-sensitive (camelCase, as listed). A last segment outside this table is treated as the tail of the func chain (implicit `eq`), so a misspelled operator is rejected by the function whitelist rather than silently changing meaning.

| Query string              | SQL                                     |
|---------------------------|-----------------------------------------|
| ?id&#124;ne=1             | `where id <> 1`                         |
| ?id&#124;gt=1             | `where id > 1`                          |
| ?id&#124;ge=1             | `where id >= 1`                         |
| ?id&#124;lt=1             | `where id < 1`                          |
| ?id&#124;le=1             | `where id <= 1`                         |
| ?id&#124;in=1,2           | `where id in (1, 2)`                    |
| ?id&#124;notIn=1,2        | `where id not in (1, 2)`                |
| ?id&#124;between=1,2      | `where id between 1 and 2`              |
| ?id&#124;notBetween=1,2   | `where id not between 1 and 2`          |
| ?id&#124;like=k%          | `where id like 'k%'`                    |
| ?id&#124;notLike=k%       | `where id not like 'k%'`                |
| ?tags&#124;intersect=a    | `where jsonb_path_exists(tags, '$[*] ? (@ == $q0)', ...)` |
| ?name&#124;isNull=        | `where name is null`                    |
| ?name&#124;isNotNull=     | `where name is not null`                |

> `isNull` / `isNotNull` take no parameter — whatever is bound to the key is ignored, because the predicate has nothing to compare against — and they compose with a func chain (`extra.age|substr(1,2)|isNull`). They are each other's negation — negate by switching to `isNotNull`; `!(...)` in `lg` only marks OR joining, it never renders NOT.

### Value shapes

Every value is placeholder-bound and never reaches the SQL text; *how many* values a key carries is decided by the operator — the comma is only a separator where the operator is inherently multi-valued:

| Operators | Values | How a String is read |
| --- | --- | --- |
| `in` / `notIn` / `intersect` | any number | split on `,` (`?id&#124;in=1,2`) |
| `between` / `notBetween` | exactly 2 | split on `,`; anything but two values throws |
| `eq` / `ne` / `gt` / `ge` / `lt` / `le` / `like` / `notLike` | exactly 1 | **the whole string is one value**; a comma is just a character |
| `isNull` / `isNotNull` | none | whatever is bound is ignored (and not counted) |

- Single-value operators never split, so a comma inside a `like` pattern, a JSON text or a name just works: `?name=John, Jr.`, `?profile={"a":1,"b":2}`. Binding several values to one **throws** instead of keeping the first (jOOQ quietly drops surplus bind values, which would mean wrong rows). Several `like` patterns go through aliases + `lg`: `l1::name|like=a%`, `l2::name|like=%b`, `lg=(l1,l2)`.
- When an element of a multi-valued operator contains a comma, pass a `List` / `Set` — containers are read element by element and inner commas are never split: `Map.of("name|in", List.of("John, Jr.", "user-2"))`. `"a,b"` is two values; `List.of("a,b")` is one value containing a comma.
- Shapes are resolved at **render time** and parsing never touches the value, so `values` inside `SelectSpec` / `LogicNode#toTreeString()` always holds exactly one element — the thing bound to the key itself.

### Naming and configuration

Everything tunable lives on `FilamentsConfig`, an **immutable object**: change one item with `withXxx` to get a new copy; pass the same instance to several Filaments for application-wide settings, or build none and each instance starts from built-in defaults. Immutability is required, not stylistic: `naming` is materialized into the column index and JSON field set at construction *and* applied live while rendering SQL, so mutating it in place splits one instance across two sets of names and writes wrong data. To reconfigure a built instance, build a new one with a new config. Column names and jsonb path segments share **one naming function** — swap it wholesale for `snake` / `camel` / `lower` / `asIs`, or compose your own:

```java
var config = new FilamentsConfig().withNaming(FilamentsConfig::camel);
// new FilamentsConfig().withNaming(s -> "t_" + FilamentsConfig.snake(s))   // custom rule: uniform table prefix

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

| Option | Default | Purpose |
| --- | --- | --- |
| `naming` | `snake` | drives condition keys, `rt`, `od`, group fields, `pk`, insert/update column names, aggregation aliases and jsonb path segments; never migrates existing data |
| `mapper` | `DEFAULT_MAPPER` | the single Jackson mapper used for all JSON read/write; must be paired with `naming` |
| `nameSplitter` | `"\|"` | separator of `field\|function\|operator` in query keys (don't use `.` — it collides with jsonb paths) |
| `aliasSplitter` | `"::"` | condition-alias separator, never reaches SQL |
| `sqlFuncList` | `PG_FUNCS` (also `MYSQL_FUNCS` / `SQLSERVER_FUNCS`) | function-chain and aggregate whitelist, replaced wholesale via `withSqlFuncList(...)` (copied immutably, so mutating your own list afterwards has no effect); operator tokens are a fixed set and are not configurable |
| `page` / `pageSize` | 1 / 20 | paging defaults: `getByPages()` fills both when absent, `get()` fills whichever of the two you left out |

- Aggregation aliases derive from the column name: `count(wechatId)` → `countWechatId`, `countDistinct(id)` → `countDistinctId`.
- Build a custom mapper with `JsonMapper.builder().propertyNamingStrategy(...).disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).build()` and keep that disable, or offset-bearing times silently shift to the local zone.

### Types and JSON

> PostgreSQL only: dotted jsonb paths render as `col #>> '{path}'` and `intersect` as `jsonb_path_exists(...)`; swapping the function preset does not make those two channels portable. The connection string must carry **`stringtype=unspecified`** (e.g. `jdbc:postgresql://host:5432/db?stringtype=unspecified`) — JSON fields are bound as JSON text, and without it the driver binds VARCHAR so PostgreSQL rejects `jsonb column = varchar expression`.

Classification happens once, at construction: JSON fields (declared `List` / `Set` / `Map` / POJO) serialize to JSON text on write, everything else is bound as a scalar; reads map columns straight into the model, with no whole-row JSON round trip. A model field declared as `Object` or as a jOOQ `JSON`/`JSONB` throws at construction — use `Map<String,Object>` / a concrete POJO / `List`, or `JsonNode` when you want the raw tree. Fields are scanned from the model class **itself** (`getDeclaredFields()`), **never from a superclass**: inherited fields are neither written nor read back (silently dropped), so use composition or redeclare them.

| Model field type | Write | Read |
| --- | --- | --- |
| `String` / numbers / `BigDecimal` / `java.time.*` / `boolean` / `byte[]` / `UUID` | bound as-is (not JSON-wrapped) | column-level mapping: assignable values set directly, others converted per value (Jackson) or via jOOQ converters (time) |
| `java.util.Date` / `java.sql.*` time fields | bound as-is | read fails loudly: timestamptz offset semantics cannot land losslessly on these types — switch to `java.time` |
| enum | stored as `name()` (e.g. `A`) | `A` → enum |
| `List` / `Set` / `Map` / nested object | serialized as JSON text (decided by declared type, not runtime value) | the read ladder first pulls the raw JSON text (`rt` projections and `SELECT *` take the same shape), then Jackson deserializes it |
| `JsonNode` (raw JSON tree) | same (also classified as a JSON field) | yields `ObjectNode` / `ArrayNode` directly; do **not** type a jsonb column as `String` — scalars take no part in JSON classification and the read fails loudly, keep raw text in `varchar` |
| jOOQ `JSON` / `JSONB` | — | rejected at construction: the read side hands back the driver's raw type or `JSONB` depending on the channel, neither of which lands on the model |

> JSON-ness is decided entirely at construction — no runtime string sniffing, so a text column whose value happens to start with `{` or `[` is read back verbatim instead of failing the query.
>
> Pre-existing camelCase jsonb keys (written by another system) read as null under the default `SNAKE`; give that table an instance with `naming = FilamentsConfig::asIs` (or `::camel`) — naming renames columns too, so this fits uniformly camelCase databases. Fields annotated with `@JsonProperty("originalKey")` keep their exact key name.

## Validation and null semantics

`create` (per row) and `updateByIds` (once) run `initValidator()` before touching the database; the first violation is thrown as a `FilamentsError` carrying yavi's own message, and a constraint name that matches no model field throws `模型 X 没有字段 y`. A batch is validated in full before the single `execute`, so one bad row writes nothing. The builder may be shared across instances and rows — the library never mutates it.

**When the root field of a constraint is `null`, that constraint is skipped entirely.** That is what makes partial updates (null = leave the column alone) and nested-path constraints (the parent may be absent) workable — at the cost that `null` cannot express "required":

| Data shape | Validated? |
| --- | --- |
| `name = null` with a `notBlank` constraint | skipped |
| `name = ""` with a `notBlank` constraint | throws `FilamentsError` |
| constraint on `address.city`, `address = null` | skipped (parent absent) |
| constraint on `address.city`, `address != null`, `city = null` | throws `FilamentsError` |
| `updateByIds(ids, patch)` | as above; null fields in `patch` don't enter the SET clause, so update cannot null out a required field |

### Required fields belong in the database

Enforce "required" with a `NOT NULL` column: a null model field renders as `insert … values (default, …)` and PostgreSQL rejects it; one INSERT is atomic, so a batch with one violating row writes 0 rows. Three cases escape it:

| Case | Behaviour | What to do |
| --- | --- | --- |
| column is `NOT NULL DEFAULT 'A'` | null is swallowed by the default — you can't tell "not provided" from "want the default" | drop the default if the value must be explicit, or check it in the `before` hook |
| empty string `''` | `NOT NULL` allows it | use yavi's `notBlank` (the value is non-null, so the constraint applies) |
| required key inside jsonb (e.g. `address.city`) | the database only sees whether the whole column is null | enforce it in the `before` hook |

> Constraint violations throw jOOQ's `IntegrityConstraintViolationException` (carrying table name, column name and SQL text), not `FilamentsError`; catch it in the service layer if you need one error exit — or want to keep physical schema out of caller-visible text (notably when it is fed back to an LLM).

## Extending

Need a sub-query or raw SQL? Subclass `Filaments`:

```java
public class UserFilaments extends Filaments<User> {
    public UserFilaments() {
        super("user");
    }

    @Override
    public ValidatorBuilder<User> initValidator() {
        return new ValidatorBuilder<>();
    }

    // row query over a sub-query: compose the protected buildSelect + doQuery (get has no sub overload)
    public List<User> getWithSub(DSLContext db, Map<String, Object> query, SelectQuery<Record> sub) {
        return doQuery(buildSelect(db, query, sub), false);
    }

    // drop down to raw jOOQ whenever you need it
    public List<Record> getByOrgId(DSLContext db, Long orgId) {
        return db.select().from(table("user")).where(field("org_id").eq(orgId)).fetch();
    }
}
```

`aggregation` takes a `sub` argument directly (`null` for the plain table):

```java
var stat = userFilament.aggregation(db,
        Map.of("count", "id"),      // targets {func: field}
        Map.of("role", "admin"),    // conditions (same query-string syntax)
        List.of("type"),            // group-by fields
        buildMySubQuery(db));       // sub-query
```

> The model type is the single `extends Filaments<Model>` generic argument on the superclass chain: deriving from a concrete subclass or using an anonymous subclass is fine, but every intermediate base class must be non-generic — `BaseF<T> extends Filaments<T>` is rejected at construction.

## FAQ

- **More complex SQL?** Point a Filaments at a database view; pass a hand-built sub-query via `aggregation`'s `sub`; or add a method in a subclass and use the injected `DSLContext` directly (see [Extending](#extending)).
- **Why didn't a null field trigger my "required" constraint?** Because a constraint whose root field is null is skipped ([Validation and null semantics](#validation-and-null-semantics)) — express "required" with `NOT NULL`.
- **Why is the first argument always `DSLContext`?** So the caller picks the connection per call (primary vs. replica) and controls the transaction context.

## Contributing & license

Issues and Pull Requests are welcome. Licensed under the [Apache License, Version 2.0](LICENSE).
