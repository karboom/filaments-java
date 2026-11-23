import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// 站点内容全部拆自仓库根的 README.md：改语法先改代码，再同步这一份文档。
// 部署方式未定（自建 OSS / Pages），但 site 必须先给：Starlight 内置 sitemap，
// 缺 site 会在构建期直接报错。定下域名后只改这一行。
export default defineConfig({
  site: 'http://localhost:4321',
  // 目录式产物（dist/xxx/index.html）+ 一律带尾斜杠：
  // 对象存储的静态托管只对 `/path/` 命中 index，不带斜杠会被当成不存在的 key
  trailingSlash: 'always',
  integrations: [
    starlight({
      title: 'Filaments Java',
      description: '为自然语言与 vibe coding 而生的轻量 Java ORM',
      favicon: '/favicon.svg',
      customCss: ['/src/css/custom.css'],
      defaultLocale: 'root',
      locales: {
        root: {
          label: '简体中文',
          lang: 'zh-CN',
        },
      },
      sidebar: [
        {
          label: '开始',
          items: ['guide/getting-started'],
        },
        {
          label: '查询语法',
          items: [
            'reference/query-syntax',
            'reference/naming-and-config',
            'reference/validation',
          ],
        },
        {
          label: '进阶',
          items: ['guide/extending', 'guide/faq'],
        },
        {
          label: '外部链接',
          collapsed: true,
          items: [
            { label: 'GitHub 仓库', link: 'https://github.com/karboom/filaments-java' },
            { label: 'Maven Central', link: 'https://central.sonatype.com/artifact/io.github.karboom/filaments-java' },
            { label: 'Javadoc', link: 'https://javadoc.io/doc/io.github.karboom/filaments-java' },
          ],
        },
      ],
      editLink: {
        pattern: 'https://github.com/karboom/filaments-java/edit/main/docs/src/content/docs/:path',
      },
      lastUpdated: true,
      social: [
        {
          icon: 'github',
          label: 'GitHub 仓库',
          href: 'https://github.com/karboom/filaments-java',
        },
      ],
    }),
  ],
});
