# server/ — 静态站点目录

**这里已经没有任何服务端代码。**

原本这里是一套 Node.js + SQLite 的云同步后端，并附带一个 Gemini 生图接口。
产品方向调整为「一次性买断 + 数据只存本机」之后，云同步与 AI 生图都已从 App 中移除，
因此整个后端连同 Docker 编排、Caddy 配置与部署脚本一并删除。

保留下来的是 `site/` 下的三张静态页面——它们仍然有用途。

## 为什么这三张页面还要留着

| 文件 | 用途 |
|---|---|
| `site/privacy.html` | Google Play 要求每个应用在商店详情页提供**隐私政策 URL**，这一条对纯本地应用同样适用 |
| `site/terms.html` | 服务条款。付费商品（一次性买断）需要说明购买与退款规则 |
| `site/index.html` | 站点首页，汇总上面两个链接，避免出现空白页 |

这些是**纯静态 HTML**：没有构建步骤、没有依赖、没有后端调用，用任何静态托管都能跑。

## 怎么托管

原来需要一台云主机 + 域名 + HTTPS 证书，是因为同步 API 必须有个常驻进程。
现在只剩静态文件，可以零成本托管，**不再需要买服务器，也不再有月费**：

| 方案 | 成本 | 说明 |
|---|---|---|
| GitHub Pages | 免费 | 把 `site/` 内容推到仓库的 `gh-pages` 分支或 `/docs` 目录，地址形如 `https://<用户名>.github.io/<仓库名>/privacy.html` |
| Cloudflare Pages | 免费 | 连接仓库后自动发布，可绑定自有域名 |
| Netlify / Vercel | 免费额度 | 同上，拖拽文件夹即可发布 |

> Play Console 的隐私政策字段接受任意公开可访问的 HTTPS 地址，
> 不要求域名与应用有任何归属关系，因此 `*.github.io` 这类地址是合规的。

## 已发布地址

站点已通过 GitHub Pages 上线，部署工作流为 `.github/workflows/deploy-pages.yml`：

| 页面 | 线上地址 | 状态 |
|---|---|---|
| 隐私政策（填入 Play Console 的就是这个） | https://chrismead1991928.github.io/chris-zhu/privacy.html | 200 |
| 服务条款 | https://chrismead1991928.github.io/chris-zhu/terms.html | 200 |
| 站点首页 | https://chrismead1991928.github.io/chris-zhu/ | 200 |

推送 `server/site/` 下的改动到 `main` 分支会自动触发重新部署，无需手动操作。
工作流只在 `server/site/**` 与该工作流文件本身发生变化时运行，改应用代码不会触发站点重新发布。

> 站点源文件位于 `server/site/` 子目录，而 Pages 内置的「从分支部署」只能指向仓库根目录或 `/docs`，
> 因此改用 Actions 上传该目录作为发布产物。**不要在 Pages 设置里改回「从分支部署」并选 `/docs`**：
> `docs/privacy-policy.html` 是一张「已废弃」提示页，会被误当作隐私政策发布出去。

## 发布前必须替换的占位符

**已全部替换完毕**（12 处），三张页面可以直接发布。当前取值：

| 占位符 | 已替换为 |
|---|---|
| `[DEVELOPER NAME]` | chris zhu |
| `[CONTACT EMAIL]` | chrismead1991928@gmail.com |
| `[EFFECTIVE DATE]` | October 8, 2026 |
| `[JURISDICTION]` | the People's Republic of China |
| `[YEAR]` | 2026 |

后续若要修改，三张页面里的同一信息必须一起改，并保持与 Play Console 的开发者资料一致——
商店页会公开显示开发者名称与联系邮箱，两者对不上时审核可能要求解释关系。

替换完成后建议在手机浏览器上打开确认一遍排版，再填入 Play Console。

## 相关文档

- Google 登录与商品配置：[`../docs/google-play-configuration.md`](../docs/google-play-configuration.md)
- 商店详情页文案：[`../docs/store-listing.md`](../docs/store-listing.md)
