import { defineConfig } from 'vitepress'

// 站内可搜文本以中文为主，MiniSearch 默认按空白切词，一整句中文会变成一个词条——
// 搜「分页」命中不了「## 分页」以外的任何写法。这里按 CJK 串切段：段内同时发
// 单字与相邻双字（双字保精度，单字保"只打了一个字"也能中），非 CJK 段仍按词切。
// 注意：本函数会被序列化进站点数据、在浏览器里还原执行，函数体必须自包含
// （不能引用外部变量，也不能依赖正则字面量以外的模块内容）。
const cjkTokenize = (text: string): string[] => {
  const CJK_RUN =
    /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufafd\u3040-\u30ff]+|[^\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufafd\u3040-\u30ff]+/g
  const CJK_HEAD = /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufafd\u3040-\u30ff]/
  const out: string[] = []

  for (const run of text.match(CJK_RUN) ?? []) {
    if (CJK_HEAD.test(run[0])) {
      for (let i = 0; i < run.length; i++) {
        out.push(run[i])
        if (i + 1 < run.length) {
          out.push(run.slice(i, i + 2))
        }
      }
    } else {
      for (const word of run.split(/[^\p{L}\p{N}_]+/u)) {
        if (word) {
          out.push(word)
        }
      }
    }
  }

  return out
}

// 部署方式未定（自建 OSS / Pages）。这里刻意不开 cleanUrls：产物是
// guide/xxx.html 平铺文件，对象存储直接按 key 命中，不需要"补 .html"的重写规则；
// 代价是地址带 .html，配置里的站内链接因此一律显式写 .html（正文里的 .md 链接
// 由 VitePress 编译期自动改写成 .html，无需手工处理）。
export default defineConfig({
  // srcDir 默认就是 docs/ 本身；README.md 是这个前端工程的说明，不是页面，
  // 不排掉的话它会被当成 /README.html 一起构建出去。
  srcExclude: ['README.md'],

  lang: 'zh-CN',
  title: 'Filaments Java',
  description: '为自然语言与 vibe coding 而生的轻量 Java ORM',

  head: [['link', { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }]],

  lastUpdated: true,

  // sitemap 只在给了 hostname 时才生成；定下域名后，这里和 public/robots.txt 一起改。
  sitemap: { hostname: 'http://localhost:4321' },

  themeConfig: {
    nav: [
      {
        text: '指南',
        activeMatch: '/guide/',
        items: [
          { text: '快速开始', link: '/guide/getting-started.html' },
          { text: '扩展用法', link: '/guide/extending.html' },
          { text: '常见问题', link: '/guide/faq.html' },
        ],
      },
      {
        text: '参考',
        activeMatch: '/reference/',
        items: [
          { text: '查询语法', link: '/reference/query-syntax.html' },
          { text: '命名与配置', link: '/reference/naming-and-config.html' },
          { text: '校验与空值', link: '/reference/validation.html' },
        ],
      },
      {
        text: '外部链接',
        items: [
          { text: 'GitHub 仓库', link: 'https://github.com/karboom/filaments-java' },
          {
            text: 'Maven Central',
            link: 'https://central.sonatype.com/artifact/io.github.karboom/filaments-java',
          },
          { text: 'Javadoc', link: 'https://javadoc.io/doc/io.github.karboom/filaments-java' },
        ],
      },
    ],

    sidebar: {
      '/guide/': [
        {
          text: '指南',
          items: [
            { text: '快速开始', link: '/guide/getting-started.html' },
            { text: '扩展用法', link: '/guide/extending.html' },
            { text: '常见问题', link: '/guide/faq.html' },
          ],
        },
      ],
      '/reference/': [
        {
          text: '参考',
          items: [
            { text: '查询语法', link: '/reference/query-syntax.html' },
            { text: '命名与配置', link: '/reference/naming-and-config.html' },
            { text: '校验与空值', link: '/reference/validation.html' },
          ],
        },
      ],
    },

    editLink: {
      pattern: 'https://github.com/karboom/filaments-java/edit/main/docs/:path',
      text: '在 GitHub 上编辑此页',
    },

    socialLinks: [{ icon: 'github', link: 'https://github.com/karboom/filaments-java' }],

    // 内置本地搜索：索引在构建期切分、运行时装进 MiniSearch，dev 下同样可用，
    // 不再需要像 Pagefind 那样整目录上传搜索资源。
    search: {
      provider: 'local',
      options: {
        translations: {
          button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
          modal: {
            displayDetails: '显示详细列表',
            resetButtonTitle: '清空搜索',
            backButtonTitle: '返回搜索',
            noResultsText: '没有匹配的结果',
            footer: {
              selectText: '选中',
              selectKeyAriaLabel: '回车键',
              navigateText: '切换',
              navigateUpKeyAriaLabel: '上箭头',
              navigateDownKeyAriaLabel: '下箭头',
              closeText: '关闭',
              closeKeyAriaLabel: 'Esc 键',
            },
          },
        },
        miniSearch: { options: { tokenize: cjkTokenize } },
      },
    },

    outline: { level: [2, 3], label: '本页目录' },
    lastUpdatedText: '最后更新',
  },
})
