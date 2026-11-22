# Catbud Addons

有一點點好用的貓芽附加模組

[真人挖掘採集工具](projects/mining_capture/README.md)：Paper 單格方塊場景，搭配 26.2／26.3 Fabric 模組收集手動操作資料。

[真人轉向第三輪個人研究](projects/mining_capture/reports/timed-review-2026-11-02/REPORT.md)已完成：較快候選比舊合併縮短約 20.7% 瞄準耗時，同輪自然度平均 5.00／4.67 分；仍保留為研究候選，未替換玩家版。

新增[空島輔助模組](projects/island_assistive/README.md)：預設關閉的附近生物提醒，預設偵測 256 格內的殭屍村民；新發現目標每 5 秒播放音效，直到點擊聊天室停止。

新增[怪物浪潮模組](projects/monster_wave/README.md)：沿用魔幻之塔的入場偵測，提供低血量與扣血預警斷線保護，支援重加入後恢復保護；已停用副本事件檔案紀錄。

## 幫助
若需要幫助，可在GitHub Issues或引導至 Discord 社群獲取支援（[點擊直達連結](https://discord.gg/XwpbGGGRk6)）

## 專案結構

```text
catbud-addons/
├── build.gradle / settings.gradle / gradle.properties
├── build-logic/
│   ├── fabric-module.gradle
│   └── settings-verification.gradle
└── projects/
    ├── catbud_core/
    │   ├── shared/src/main/       # 設定結構、儲存、註冊、資源
    │   ├── shared/src/modern/     # 26.1.2、26.2 與 26.3 共用的設定介面
    │   ├── shared/src/test/
    │   └── versions/{1.21.11,26.1.2,26.2,26.3}/
    │       ├── build.gradle / version.properties
    │       └── src/main/java/    # 客戶端、輸入與 HUD 轉接層；舊版設定介面
    ├── island_assistive/
    │   ├── shared/src/main/       # 生物提醒、名單、音效、設定與資源
    │   ├── shared/src/modern/     # 新版名單畫面與客戶端指令
    │   ├── shared/src/test/
    │   ├── shared/src/verification/
    │   └── versions/{1.21.11,26.1.2,26.2,26.3}/
    └── magic_tower/
        ├── shared/src/main/      # 工作階段、掃描、尋路、HUD 演算法、資源
        ├── shared/src/test/
        ├── shared/src/verification/
        └── versions/{1.21.11,26.1.2,26.2,26.3}/
            ├── build.gradle / version.properties
            └── src/main/java/    # 路線渲染與版本專用封包 Mixin
```

請使用 **JDK 25** 執行此儲存庫的 Gradle Wrapper；舊版目標則會使用 `--release 21` 進行編譯。

## 編譯與打包

```powershell
.\gradlew.bat assemble
.\gradlew.bat releaseJars
.\gradlew.bat :projects:magic_tower:26.1.2:assemble
```

`assemble` 會打包原有二十種「模組 × 版本」組合，以及真人採集的 Paper 插件與兩個客戶端版本，但不會執行測試或啟動客戶端。單一模組版本可用 `./gradlew.bat :projects:island_assistive:26.2:assemble` 打包。

`releaseJars` 會將四個版本的 Core、Magic Tower、公共傳點、怪物浪潮與空島輔助可安裝 JAR 輸出至 `out/`。
Magic Tower、公共傳點、怪物浪潮與空島輔助 JAR 不內嵌 Core。玩家須另外安裝一份相同 Minecraft 版本的 Core，供這些附加模組共用。原有模組版本為 **1.0.2**；獨立真人採集的 Paper 插件為 **1.1.0**、客戶端為 **1.0.0**，不需要 Core。

26.1.2 的產物為：

```text
out/catbud-core-26.1.2-1.0.2.jar
out/catbud-magic_tower-26.1.2-1.0.2.jar
out/catbud-public_warps-26.1.2-1.0.2.jar
out/catbud-monster_wave-26.1.2-1.0.2.jar
out/catbud-island_assistive-26.1.2-1.0.2.jar
```

開發用與原始碼產物位於 `projects/<module>/versions/<minecraft>/build/libs/`。各個獨立產物位於：

```text
projects/<module>/versions/<minecraft>/build/libs/
```
