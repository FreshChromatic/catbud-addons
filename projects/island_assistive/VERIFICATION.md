# 空島輔助驗證紀錄

新增的固定座標自動挖掘功能另見[挖掘驗證紀錄](MINING_VERIFICATION.md)。下表與原有內容記錄生物提醒功能；挖掘的遊戲內驗證以 26.2 及指定地圖副本為基準。

驗證日期：2026-10-30。環境為 Windows、JDK 25、Fabric Loader 0.19.5；各版本 Fabric API 沿用對應 version.properties。

| Minecraft | 建置與打包 | 行為測試 | 遊戲內驗證 |
| --- | --- | --- | --- |
| 1.21.11 | 通過，Java 21 目標 | 25 個通過 | 通過 |
| 26.1.2 | 通過，Java 25 目標 | 25 個通過 | 通過 |
| 26.2 | 通過，Java 25 目標 | 25 個通過 | 通過 |
| 26.3 | 通過，Java 25 目標 | 25 個通過 | 通過 |

更新引擎另外執行 42 個測試，全部通過，包含空島輔助安裝拓撲與模組識別。專案 JDT 格式及 Java 控制流程檢查通過。

## 驗證內容

- 預設關閉、不追蹤生物；預設距離 256 格、預設名單僅殭屍村民。
- 2 秒初始基準只合併通知；後續目標立即響鈴，5 秒到期才重播，卡頓不補播。
- 名稱延遲到達、種類與名稱組合、多規則命中、同 UUID 出入範圍或卸載重載去重。
- 變更距離或名單重新建立基準，保留既有待確認提醒。
- 目標消失後持續提醒；單項確認、批次確認、過期工作階段與重複點擊行為。
- 名單儲存與重讀、清空後維持空白、損壞檔案備份與儲存失敗保留原狀。
- 四個版本以實際遊戲客戶端驗證實體載入事件、聊天室點擊執行本機指令、音效停止與資源重載後重播。
- 四個版本實際操作名單畫面新增並儲存名稱規則，檢查 320×240 介面控制項沒有重疊或超出邊界。
- 四個版本檢查千隻生物觀測、垂直距離 257 格排除及進入 256 格後可被觀測，並確認排除玩家及釋放卸載實體參照。

千隻生物測試的最後一次記錄，每次觀測平均約 1.02–1.19 毫秒；這是本機測試結果，並非所有電腦與伺服器的效能保證。

遊戲內測試使用指定名稱的殭屍村民，避免測試世界自然出現的其他殭屍村民影響提醒數量判定。這項篩選僅用於驗證程式，正式模組仍預設不限名稱。

## 產物檢查

四個可安裝 JAR 位於根目錄 out，版本均為 1.0.2。已逐一檢查模組識別、Minecraft 與 Core 依賴、Java class 版本、中英文語系及音效資源；不包含驗證程式、測試類別或內嵌 Core。

打包音檔與指定來源逐位元組相同，SHA-256：

`50478cc41f252c160ac4051ec249ee479c0f4bdc41e73c684982a25a7d84396d`

各版本原始遊戲內報告及截圖位於：

```text
versions/<minecraft>/build/verification-island-assistive/island-assistive-verification.txt
versions/<minecraft>/build/verification-island-assistive/screenshots/island-alert-chat.png
versions/<minecraft>/build/verification-island-assistive/screenshots/island-watchlist.png
```

## 重現

```powershell
.\gradlew.bat :projects:island_assistive:1.21.11:build :projects:island_assistive:26.1.2:build :projects:island_assistive:26.2:build :projects:island_assistive:26.3:build :update-engine:test --max-workers=2
.\gradlew.bat :projects:island_assistive:1.21.11:runClient :projects:island_assistive:26.1.2:runClient :projects:island_assistive:26.2:runClient :projects:island_assistive:26.3:runClient -PverifyIslandAssistive --max-workers=1
```

客戶端驗證在各版本獨立的 build 資料夾建立測試世界，不需要連入外部伺服器。正式功能仍受伺服器實體追蹤距離限制，首次發現不代表已確認出生事件。
