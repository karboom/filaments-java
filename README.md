# 简介
提供了一组语法可以将查询字符串直接转换为SQL查询，并且提供了一组NLP友好的数据操作接口

# 查询字符串
## 保留关键字
| 字段名称 | 功能                | 默认值  | 查询字符串                                             | 对应SQL                                                             |
|------|-------------------|------|---------------------------------------------------|-------------------------------------------------------------------|
| rt   | 指定返回字段            | *    | ?rt=name,age                                      | ```select `name`, `age` ```                                       |
| p    | 指定当前页码            | 1    | ?p=3                                              | ```limit 20 offset 40```                                          |
| pc   | 指定页内行数            | 20   | ?pc=18                                            | ```limit 18 offset 0```                                           |
| od   | 指定排序规则            | Null | ?id,-height                                       | ``` order by `id` asc, `height` desc```                           |
| lg   | 指定字段的逻辑组合         | ()   | ?height=18&weight=30&size=100&lg=(!(size,weight)) | ``` where (`height` = 18 and (`size` = 100 or `weight` = 30)) ``` | 
| pg   | 是否需要计算分页(仅分页函数生效) | 0    | ?pg=1                                             | ``` limit ? offset ? ```                                          |


## 字段表达式
### 格式
字段名|函数(参数)...|操作符=数值

### 示例
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

# 使用方法
## 示例
``` java
TODO
```

# QA
- 更为复杂的SQL如何支持？
>
> 1.在数据库编写视图
>
> 2.通过sub参数传入Knex构造的子查询
>
> 3.继承Filaments类，扩展自定义方法，通过db.raw()直接编写语句

- 为什么第一个参数都是DSLContext？
>
> 1.根据业务需求灵活的选择数据库连接（主库或者从库）
>
> 2.灵活的选择事务上下文


# 提示词工程
测试于 ?

## 功能函数
```text
基于给定的数据表、工具类、代码风格、功能描述生成ts函数，只需要函数代码块本身即可,无需import，也不需要做出解释

数据库建表语句：
CREATE TABLE `role`
(
`id`          bigint       NOT NULL,
`name`        varchar(20)  NOT NULL,
`org_id`      bigint       NOT NULL comment '组织id',
`create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
`grant`       json         NOT NULL COMMENT '权限打包列表',
`remark`      varchar(100) NOT NULL DEFAULT '',
`is_delete`   int          NOT NULL DEFAULT '0',
PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

工具类代码：
* 命名空间util
export const check_power = async(db: Knex, id: string) {}
* 命名空间data
export const get_time_now = async() {}
* 数据库对象
 class Filaments<T> {
  static DEFAULT_QUERY_PC: number;
  static DEFAULT_QUERY_P: number;
  static NAME_SPLITTER: string;
  static OP_ALIAS: {
  [key in string]: string[];
  };
  static SQL_FUNC_LIST: string[];
  json_fields: string[];
  maps: {};
  before: Function | null;
  after: Function | null;
  schema: object;
  table: string;
  pk: string;
  constructor(table: string, schema: object, maps: object, pk?: string);
  /**
    * 默认的校验处理器
      */
      protected default_schema_handler: (S: object, data: T) => object;
      /**
    * JSON字段处理
      */
      protected json_handler: (data: any, func: Function) => any;
      /**
    * 处理Joi校验格式
      */
      normalize_schema: (schema: object) => Joi.Schema;
      /**
    * 过滤字段包裹字符串
      */
      private field_name_safe;
      /**
    * 过滤函数名
      */
      private func_name_safe;
      create(db: Knex, data: T[] | T, schema_handle?: Function | null): Promise<number[]>;
      delete_by_ids(db: Knex, ids: Ids): Knex.QueryBuilder<{}, number>;
      update_by_ids(db: Knex, ids: Ids, data: T, schema_handler?: Function | null): Knex.QueryBuilder<{}, number>;
      build_return(db: Knex.QueryBuilder, query: Query): Knex.QueryBuilder;
      build_sub(db: Knex, sub: Sub): Knex.QueryBuilder;
      build_order(db: Knex.QueryBuilder, query: Query): Knex.QueryBuilder<any, any>;
      /**
    * 构建查询条件
      */
      build_condition(db: Knex.QueryBuilder, query: Query): Knex.QueryBuilder<any, any>;
      protected build_select(db: Knex, query: Query, sub: Sub): Knex.QueryBuilder<any, any>;
      protected do_query(query: Knex.QueryBuilder, lock?: boolean): Promise<T[]>;
      /**
    * 条件查询
      */
      get(db: Knex, query: Query, sub?: Sub): Promise<T[]>;
      /**
    * id查询
      */
      get_by_ids(db: Knex, ids: Ids, lock?: boolean): Promise<T[]>;
      /**
    * 分页查询
      */
      pages(db: Knex, query: Query, sub?: Sub): Promise<{
      data: T[];
      count: number;
      pages: {
      total: number;
      now: number;
      };
      }>;
      aggregation(db: Knex, target: AggregationTarget, query?: Query, group?: string | string[], sub?: Sub): Knex.QueryBuilder;
      /**
    * 直接返回knex.QueryBuilder,可以根据需要追加参数
    * 1.可以通过Mysql2驱动的 .options({rowsAsArray: true}) 返回数组
    * 2.可以通过.stream返回流
      */
      get_raw(db: Knex, query: Query, sub?: Sub): Knex.QueryBuilder;
      }

export const Role = new Filaments()

代码风格：
*变量名采用驼峰形式
*第一个参数为 DSLContext db ，第二个参数为 String userId 表示当前用户id
*未要求的情况下，不使用try catch，异常情况直接抛出错误类Error.make()

功能描述：
获取特定组织下的所有角色，遍历数组,判断是否有修改权限，修改名称为角色id+当前时间，然后更新数据

```

## 测试用例
```text
TODO
```

