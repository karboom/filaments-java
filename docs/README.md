# 文档站（VitePress）

库的介绍页与查询语法参考。内容拆自仓库根的 `README.md` / `README.en.md`，
**改语法先改代码，再同步这里的页面**。站点是独立 npm 工程，`srcDir` 就是 `docs/` 本身
（不能放仓库根，否则 Markdown 会和 Java 的 `src/` 撞在一层）。

本站不是页面、而是工程说明的这个文件（`docs/README.md`），靠 `srcExclude: ['README.md']`
从构建里排掉——不加这一行它会变成 `/README.html` 一起发出去。

## 本地跑

VitePress 1.6 要求 Node.js >= 18；用 nvm 里已有的 24 即可：

```bash
export PATH="$HOME/.nvm/versions/node/v24.21.0/bin:$PATH"
cd docs
npm install
npm run dev        # http://localhost:5173
npm run build      # 产物在 docs/.vitepress/dist/
npm run preview    # 预览构建产物（默认端口 4173，可跟位置参数：npx vitepress preview --port 5199）
```

## 目录

```
.vitepress/config.mts        站点配置：语言、nav/sidebar、搜索、编辑链接、sitemap
.vitepress/theme/index.ts    继承默认主题，只做一件事：挂 custom.css
.vitepress/theme/custom.css  品牌色（--vp-c-brand-*）+ 中英混排字体栈
index.md                     首页（layout: home + hero + features）
guide/getting-started.md     快速开始
guide/extending.md           扩展用法
guide/faq.md                 常见问题
reference/query-syntax.md    保留关键字 / 字段表达式 / 操作符 / 值的形态
reference/naming-and-config.md  配置项 / 类型与 JSON 约定
reference/validation.md      校验与空值 / 必填交给数据库
public/                      favicon、robots.txt（构建时整目录拷到产物根）
```

页面与 README 章节的对应关系：

| 页面 | README.md 章节 |
| --- | --- |
| `index.md` | 顶部标语 + `为什么适合自然语言` + `快速开始` 片段 |
| `guide/getting-started` | `快速开始` |
| `reference/query-syntax` | `查询字符串语法` 全部四个子节 |
| `reference/naming-and-config` | `命名与配置` + `类型与 JSON 约定` |
| `reference/validation` | `校验与空值` + `必填交给数据库` |
| `guide/extending` | `扩展用法` |
| `guide/faq` | `常见问题` + `贡献与许可` |

## 从 Starlight 迁过来的几处不同

| 事项 | 现在 |
| --- | --- |
| 内容格式 | Markdown（不再需要 MDX），正文里不写 JSX 组件 |
| 首页卡片 | frontmatter `features`（`details` 走 `v-html`，可以放 `<code>`，但不能写反引号）；正文里的"下一步"用普通链接列表 |
| 容器 | `::: info` / `::: tip` / `::: warning` / `::: danger`，标题跟在类型后一个空格（`::: warning 连接串必须带 ...`），不再是 `:::caution[标题]` |
| 站内链接 | 正文写相对 `.md`（`query-syntax.md`、`../reference/validation.md`），编译期自动改写成产物 URL；`config.mts` 里的 `nav`/`sidebar` 与首页 `hero`/`features` 是运行时字符串，不经 Markdown 编译，必须显式写 `.html` |
| 搜索 | 内置本地搜索（MiniSearch），构建期切段、运行时装索引；没有 `pagefind/` 目录要上传了，dev 模式也能搜 |
| 产物位置 | `docs/.vitepress/dist/`（不再是 `docs/dist/`） |
| 别名/编辑链接 | `editLink.pattern` 用 `:path` 占位，替换成相对 `docs/` 的源文件路径 |

## 搜索的中文分词

MiniSearch 默认按空白切词，中文一整句会变成一个词条，搜「分页」什么都搜不到。
`config.mts` 里的 `cjkTokenize` 按 CJK 串切段，段内同时发单字与相邻双字（双字保精度、
单字兜"只打了一个字"），非 CJK 段仍按词切。

这个函数会被序列化进站点数据、在浏览器里用 `new Function` 还原执行，所以：

- 函数体必须自包含，不能引用外部变量；
- 部署站若开启严格 CSP（禁 `unsafe-eval`），还原会失败、搜索框报错——届时改为
  去掉自定义 `tokenize`，或把 CSP 放开该脚本。

## 部署前必须改的两处

域名还没定，先把占位处理写清楚：

1. `config.mts` 的 `sitemap.hostname`（当前 `http://localhost:4321`）：sitemap 只在给了
   hostname 时才生成，其中的绝对地址全部来自这一行。
2. `public/robots.txt` 里的 `Sitemap:` 行，指向 `<域名>/sitemap.xml`（VitePress 是单文件，
   不是 Starlight 的 `sitemap-index.xml`）。

`cleanUrls` 保持默认关闭：产物是 `guide/xxx.html` 平铺文件，对象存储按 key 直接命中，
不需要"补 .html"的重写规则。代价是地址带 `.html`，所以上面说的运行时链接一律要写 `.html`。
若日后换成会自动补后缀的托管（Vercel/Netlify）并开 `cleanUrls: true`，`nav`/`sidebar`/`hero`/
`features` 里的 `.html` 得一并去掉，否则链接和 sitemap 会对不上。

子路径部署（如 `https://x.github.io/filaments-java/`）要加 `base: '/filaments-java/'`，
`public/` 里的绝对路径与 sitemap 会随之改，站点数据里 `head` 的 favicon 也要跟着写成带 base 的路径。

## 构建产物的 URL 行为（实测）

| 请求 | `vitepress preview` | 对象存储静态托管 | 说明 |
| --- | --- | --- | --- |
| `/` | 200 | 200 | |
| `/guide/getting-started.html` | 200 | 200 | 站内链接的实际形态 |
| `/guide/getting-started` | 200 | **404** | preview 会自己补 `.html`，OSS 不会——别拿 preview 的宽松行为判断可用性 |
| `/guide/getting-started/` | 200 | **404** | 同上，产物没有目录式 `index.html` |
| `/sitemap.xml`、`/robots.txt`、`/favicon.svg` | 200 | 200 | |
| `/404.html` | 200 | 200 | 需要 OSS 把 404 规则指到这个 key，否则浏览器看到的是 OSS 默认 XML 错误页 |

上传整个 `.vitepress/dist/` 即可，搜索索引在页面数据里，没有额外目录要带。

## 几条写文档时的约定

- 代码块语言标 `groovy` 不要标 `gradle`：Shiki 的 `gradle` 语法在某些版本缺位，会退化成纯文本。
- 站内链接一律相对 `.md`（编译期改写），不要在正文里写 `/guide/xxx/` 这种绝对路径——绝对路径
  不经改写，`base` 一变就全断。
- 表格里要出现 `|` 用 `&#124;`（VitePress 的 Markdown 表格同样把 `|` 当列分隔符）。
- 泛型在正文里用反引号包好（`` `PageResult<T>` ``），否则尖括号会被当成 HTML 标签吞掉。
- `lastUpdated` 取的是 git 提交时间：文件没提交过就没有"最后更新"，构建不报错但页脚不显示。

## 待办

- [ ] 英文站：`config.mts` 加 `locales` 的 `/en/`，内容按 `README.en.md` 切同样七篇
- [ ] 首页交互 demo：输入 `age|gt=18` 实时渲染 SQL（VitePress 支持在页面里挂 Vue 组件，
      `.vitepress/theme/index.ts` 注册即可，不必额外引框架）
- [ ] 安全模型单独成页（白名单校验、占位符绑定、`raw` 的取舍）
- [ ] 定部署目标并补 CI（`npm ci && npm run build` + 产物上传）
