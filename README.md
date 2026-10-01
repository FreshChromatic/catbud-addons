# Catbud Addons

有一點點好用的貓芽附加模組

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
    │   ├── shared/src/modern/     # 26.2 與 26.3 共用的設定介面
    │   ├── shared/src/test/
    │   └── versions/{1.21.11,26.2,26.3}/
    │       ├── build.gradle / version.properties
    │       └── src/main/java/    # 客戶端、輸入與 HUD 轉接層；舊版設定介面
    └── magic_tower/
        ├── shared/src/main/      # 工作階段、掃描、尋路、HUD 演算法、資源
        ├── shared/src/test/
        ├── shared/src/verification/
        └── versions/{1.21.11,26.2,26.3}/
            ├── build.gradle / version.properties
            └── src/main/java/    # 路線渲染與版本專用封包 Mixin
```

請使用 **JDK 25** 執行此儲存庫的 Gradle Wrapper；舊版目標則會使用 `--release 21` 進行編譯。

## 編譯與打包

```powershell
.\gradlew\.bat assemble
.\gradlew\.bat releaseJars
.\gradlew\.bat :projects:magic_tower:26.2:assemble
```

`assemble` 會打包全部六種「模組 × 版本」組合。

`releaseJars` 還會將三個可安裝的 Magic Tower JAR 收集至 `build/releases/`：

```text
catbud-magic_tower-1.21.11-1.0.jar
catbud-magic_tower-26.2-1.0.jar
catbud-magic_tower-26.3-1.0.jar
```

各個獨立產物位於：

```text
projects/<module>/versions/<minecraft>/build/libs/
```
