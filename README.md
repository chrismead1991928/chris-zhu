# Family Recipe Box / 家庭菜谱

使用 Jetpack Compose + Material3 + Room 构建的**纯本地**菜谱卡片应用，正式包名为 `com.familyrecipebox.app`，支持中/英/日/德/法 5 种语言。

应用没有账号体系、没有服务端、也不发起任何网络请求：菜谱、照片与设置全部保存在本机数据库中。唯一的对外依赖是 Google Play Billing（一次性买断商品），且购买流程由 Play 商店进程通过 IPC 完成。

## 数据安全：两条腿

云同步被移除之后，用户数据只存在本机，因此「不丢数据」必须由另外两件事保证：

1. **照片从选图那一刻就复制进应用私有目录**（`filesDir/recipe_images/`），数据库只保存这个私有文件的地址。
   如果只记住相册给的 `content://` 地址，那个读取授权是临时的——重启设备、用户在相册里删掉原图、把照片挪到别的相册，配图就会变空白。
2. **「备份与恢复」提供完整的导出与导入通道**：导出把菜谱连同照片打包成一个 ZIP，导入支持「合并」与「整库替换」两种策略，换机后可以完整还原。

关键取舍：**导入永远不会被免费版上限拦下**。恢复数据是「拿回自己的东西」，任何「因为你不是会员所以不让你恢复」的逻辑都会直接毁掉用户对应用的信任；20 道上限只约束日后新增。

## 分类：一道不许绕过的闸门

分类值（`Main` / `Side` / `Salad` / `Dessert`）是**内部存储 key**，主界面底部页签就是按这个集合渲染的，`filteredRecipes` 只做 `it.category == 当前页签` 这一种匹配。

由此得到一个硬不变量：**写进数据库的 `category` 必须落在 `RECIPE_CATEGORIES` 内**。否则菜谱在所有页签下都看不到（但仍然会被导出，因为导出走全表查询、不看分类），表现为「数据库里有、界面没有、备份却带走了」这种最难排查的状况。

- 唯一来源：`data/RecipeCategories.kt` 提供 `RECIPE_CATEGORIES`、`DEFAULT_RECIPE_CATEGORY` 与 `normalizeRecipeCategory()`。放在 data 层是因为它同时被数据库、仓库、备份导入使用；此前它定义在 UI 层，既造成数据层反向依赖 UI 层，也埋下了写入非法分类的隐患。
- 写入闸门：`RecipeRepository.normalizeForLocalWrite()` 对**每一条**本地写入调用 `normalizeRecipeCategory()`，这是最后一道防线，任何绕过上层逻辑的写入都会被它接住。
- 兜底值取 `Main`：主界面默认页签，保证异常分类的菜谱至少**立刻可见**。此前历史代码用 `Dessert` 兜底，实测会让菜谱落到非当前页签上，用户以为「没保存成功」。**新建菜谱的默认分类改为「用户当前浏览的页签」**，通过路由参数 `edit/{recipeId}?defaultCategory={defaultCategory}` 带入编辑页。
- 备份导入：`ImportPlanner.toEntity()` 同样走 `normalizeRecipeCategory()`，避免坏备份把非法分类灌回库里。
- 历史数据修复：数据库版本 7 → 8 新增 `MIGRATION_7_8`，把存量记录里 `NULL` / 空白 / 不在规范集合内的分类统一改写为 `Main`。不新增列，只做数据订正。

`RecipeCategoriesTest` 把这条不变量固化成断言：任意输入（含历史遗留的 `"Other"`、大小写变体、空白）规范化后必定落在 `RECIPE_CATEGORIES` 内，且兜底值本身必须是页签之一——兜底值一旦落在集合外，兜底逻辑自己就会制造「菜谱消失」。

## 商业模式

| | 免费版 | 完整版 |
|---|---|---|
| 菜谱数量 | 最多 20 道 | 不限 |
| 备份与恢复 | ✅ | ✅ |
| 价格 | 免费 | 一次性买断，无订阅、无周期扣费 |

买断商品 ID 为 `premium_unlock`（Play Console 中需创建为**一次性商品**）。代码内部保留 `FreeTierLimiter.PREMIUM_RECIPE_LIMIT = 1000` 作为防御异常批量写入的安全上限，但对外一律表述为「无限」。

## 功能

- 主界面以网格展示菜谱卡片，支持 1~4 列缩放。
- 点击 + 添加新菜谱，点击卡片进入编辑界面。
- 编辑页支持修改标题、从相册更换图片、编辑步骤与要点；照片会被复制进应用私有目录，不依赖相册的临时授权。
- **设置页最上方是「备份与恢复」**：导出把菜谱与照片打包成一个 ZIP；导入时先解析并展示「这份备份里有多少菜谱、多少照片、什么时候导出的」，再由用户选择合并或整库替换。
- 编辑页提供「复制提示词」：按当前菜名、分类、步骤与要点在**本地**拼装一段绘图提示词，供用户自行粘贴到任意生图工具。纯字符串拼装，无网络请求、无调用成本。
- **详情页顶栏可把菜谱分享成一张竖向长图**（含照片、标题、分类、评分、编号步骤、要点与品牌页脚），通过系统分享面板发给任意应用。渲染完全在本地完成，不需要任何存储权限。
- 底部西式分类（Main / Side / Salad / Dessert），支持长按重命名。
- 顶部骰子随机选菜。
- 免费版达到 20 道上限时给出说明型弹窗（先讲清处境，再给购买入口），并在标题栏显示计数徽标。
- 设置页支持语言切换、分类名重置、使用攻略、升级入口、分享、打分。
- 所有数据使用 Room 持久化保存（当前数据库版本 8）。
- 适配 Edge-to-Edge，支持 Android 15 预测返回手势。

> 数据层的 `serverId` / `syncedAt` / `imageLocalOnly` 字段在云同步移除后**刻意保留**：
> 它们已写入 Room 表结构，删列意味着要新增一次数据库迁移，而收益仅是列名好看。
> 其中 `imageLocalOnly` 在无同步场景下仍然有意义（标记本机私有图片地址）；
> `serverId` 还额外承担了备份导入时的身份识别——它跨设备保持不变，导入方靠它判断「这条菜谱本机已经有了」。

## 备份文件格式

备份是一个普通 ZIP，可以自己解开查看：

```
manifest.json       格式版本、导出时间、数量统计（先读它，用于快速判定文件是否合法）
recipes.json        菜谱数组，字段刻意与 Room 实体解耦
images/img-0001.jpg 用户照片原图，通过 recipes.json 里的 imageEntry 与菜谱关联
```

磁盘格式与数据库表结构**解耦**是刻意的：备份是可能被一年后的新版本读取的文件，把两者绑死意味着数据库一旦改列，旧备份就变成废纸。

解析器面对的是**任意字节流**（导入时文件选择器不按类型过滤，否则用户会「找不到自己转存过的备份」），因此对每个条目都有字节上限、累计上限与路径穿越校验，并拒绝格式版本高于当前应用的备份而不是猜着读。这套逻辑由 `app/src/test/` 下的测试覆盖。

## 分享长图的实现取舍

长图是**手写 Canvas 绘制**的，不是界面截图，也不是渲染一个不可见的 ComposeView：

- 分享图需要的是品牌化版面（大标题、编号步骤、品牌页脚），而不是应用界面截图；
- 脱离屏幕的 ComposeView 要凑齐生命周期与 composition 环境，在无界面场景下很脆弱；
- 手写绘制无环境依赖，可在任意线程执行，尺寸完全可控。

渲染分两步：**先排版**（构建全部 `StaticLayout` 并累加出精确高度）**再绘制**。顺序不能反——Bitmap 高度必须在创建时就确定，而高度取决于文字折行结果；反过来就只能开一张超高画布再裁剪，白白多占几倍内存。所有绘制指令先记录成列表、再统一执行，让排版与绘制共用同一份坐标，避免两处各算一套导致错位。

几个具体决定：

- **图片外借走 FileProvider，只开放 `cacheDir/shared_cards/` 一个子目录**。既不需要存储权限，也不会把应用私有目录暴露出去；分享图放在缓存目录，系统存储紧张时可直接回收。
- **JPEG 95 而非 PNG**。长图高达数千像素，PNG 会有好几 MB，JPEG 在画质与体积之间更划算。
- **Bitmap 内存有硬上限**。位图占用 = 宽 × 高 × 4 字节，无上限时一段极端冗长的菜谱就能直接 OOM；超限后停止追加内容，并对渲染全程做 `OutOfMemoryError` 兜底。
- **用户输入一律限行省略**。标题、步骤、要点最多 8 行，分类名（可被用户重命名成长句）超出宽度自动省略，避免版面被撑破。
- **文件名由标题净化而来**，剔除路径分隔符等非法字符并折叠、截断，失败则回退到 `recipe`。这套规则与缓存清理策略是纯函数，由单元测试压住。

分享入口放在详情页顶栏而不是卡片上：主界面卡片可缩放到 4 列，窄至约 70dp，再塞一个图标会把菜名挤没；而长按已经被「拖拽排序」占用。放在详情页则与网格密度无关，且用户此时正看着这道菜的内容。

## 应用名的唯一来源

`app_name` 是品牌名**唯一**的定义处，它同时决定三件事：桌面图标名、分享长图上的品牌署名、以及推荐语里的名字。

其他任何文案都**不得把名字硬写进译文**，必须通过 `%1$s` 格式参数注入 `app_name`。原因很直接：商店里的应用名是**本地化**的（家庭菜谱 / ファミリーレシピボックス / Familien Rezeptbox / Boîte à Recettes Familiales），如果页脚或推荐语里写死英文名，非英语用户拿着图或推荐语去商店搜，**根本搜不到**——传播链路就断在这里。目前通过格式参数注入名字的有 `share_image_tagline`、`share_app_message`、`share_app_summary`、`backup_import_invalid`。

`app_name` 本身必须与 Play Console 里各语言的商店名保持一致，改一处就要同步另一处。

## 技术栈

- compileSdk / targetSdk：35
- minSdk：26
- Kotlin：2.0.21
- Compose BOM：2024.12.01
- Material3：1.3.1
- Room：2.6.1
- Coil：2.7.0
- Navigation Compose：2.8.5
- ViewModel：2.8.7
- Google Play Billing：7.0.0
- Gson：2.11.0（Room 列表字段类型转换器 + 备份清单与菜谱的 JSON 读写）

## 如何构建 APK

1. 打开 Android Studio（推荐 Ladybug 2024.2.1 或更新版本）。
2. 选择 **Open**，定位到本目录 `recipe-cards-android`，等待 Gradle 同步完成。
3. 如果提示缺少 SDK，按向导下载 Android SDK 35 与 Build Tools。
4. 同步成功后，点击菜单 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
5. 构建完成后，APK 通常位于：
   `app/build/outputs/apk/debug/FamilyRecipeBox-v1.0.1-debug.apk`

命令行构建（Windows PowerShell）：

```powershell
$env:JAVA_HOME='<项目目录>\android-build-tools\jdk21\jdk-21.0.12.1+1'
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:bundleRelease --console=plain
```

运行单元测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

## 资源一致性自检

```powershell
python _check_app_sources.py
```

检查 5 项：Kotlin 文件 BOM、strings.xml 合法性与重名、跨语言 key 集合一致、跨语言格式化占位符一致、**是否存在源码从未引用的死文案**。

第 5 项在功能删除后尤其重要：被删功能的文案不会报错，但会一直占着 5 个语言文件并误导后续维护者。

## Google Play 商品配置

应用不含 Google Sign-In，因此**不需要** Google Cloud 项目、OAuth 客户端 ID 或同意屏幕配置。只需要创建买断商品：

- 逐字段填写清单：[`docs/play-console-product-setup.md`](docs/play-console-product-setup.md)
- 商品 ID 定义在 `app/src/main/java/com/familyrecipebox/app/billing/BillingManager.kt` 的 `PREMIUM_UNLOCK` 常量，需与 Play Console 一致。

## 隐私政策托管

`server/` 目录**已不包含任何服务端代码**，只保留 `site/` 下三张静态页面（主页、隐私政策、服务条款）。
Play 商店要求提供隐私政策 URL，但这三张页面可以免费静态托管，不再需要云主机与域名。

详见 [`server/README.md`](server/README.md)。

## 项目结构

```
recipe-cards-android/
├── app/build.gradle.kts          # 模块构建配置与依赖
├── build.gradle.kts              # 项目级插件版本
├── settings.gradle.kts           # 仓库与模块配置
├── gradle/wrapper/...            # Gradle Wrapper
├── _check_app_sources.py         # 资源与源码一致性自检
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/familyrecipebox/app/
│   │   ├── MainActivity.kt
│   │   ├── RecipeCardsApp.kt
│   │   ├── RecipeCardsApplication.kt
│   │   ├── backup/               # 备份格式、图片私有仓库、读写器、导入判定
│   │   ├── billing/              # Play 一次性买断与免费额度限制
│   │   ├── data/                 # RecipeCard、Dao、Database、Repository、分类规范（RecipeCategories）
│   │   ├── share/                # 分享长图：Canvas 渲染、FileProvider 外借、文件策略
│   │   ├── ui/home/              # 主页网格、分类、随机选菜、免费版计数徽标
│   │   ├── ui/edit/              # 编辑界面、本地提示词拼装、分享入口
│   │   ├── ui/settings/          # 设置界面（含备份与恢复）
│   │   ├── ui/premium/           # 买断弹窗与免费版上限说明弹窗
│   │   ├── ui/common/            # 通用组件
│   │   ├── ui/theme/             # 颜色、字体、主题
│   │   └── util/                 # 启动追踪、崩溃记录、语言切换
│   └── res/
│       ├── drawable/             # 默认菜谱图片
│       └── xml/                  # 备份规则、语言列表
├── app/src/test/                 # 备份解析、导入判定、分类规范化的单元测试
├── server/site/                  # 隐私政策 / 服务条款 / 站点首页（纯静态）
├── dist/                         # 分发产物（APK / AAB）
├── docs/                         # 配置文档与商店素材
└── README.md
```

## 默认菜谱

- Spaghetti Carbonara
- Caesar Salad
- Creamy Mashed Potatoes
- Fluffy Pancakes

## 内置插画的绘制约定

新增内置菜谱插画（放在 `app/src/main/res/drawable/`）时，请避免使用纯白色背景。
参考现有图片的暖米色 / 奶油色背景（约 `#FFF5E6` / `#FFF8ED`），使全部卡片在网格中保持统一、柔和的视觉效果。
