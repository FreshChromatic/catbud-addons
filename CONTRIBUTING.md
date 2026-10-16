# 開發與程式碼整理

所有項目遵循 [AGENTS.md](AGENTS.md)。請使用具名方法拆分獨立流程，將領域規則放在負責該資料的類別，並保留必要的協定、相容性與安全邊界說明。

使用 JDK 25、Node.js 24 以上及 `rg`，在儲存庫根目錄執行：

```powershell
.\tools\format-code.ps1
.\tools\format-code.ps1 -Check
.\gradlew.bat build
npm --prefix services/public-warps test
```

Java 排版參考 `references/SunLight-spigot`：同組欄位名稱與等號對齊，依責任以空行分組；簡單賦值與呼叫保持緊湊，長呼叫依參數語意換行。使用四空格縮排及續行縮排，建議行寬 120 字元；清楚的方法宣告可超出，不必為了行寬逐層拆開。條件和迴圈保留明確區塊，Java 巢狀三元運算會被檢查拒絕。

`tools/format-code.ps1` 使用固定版本 Eclipse JDT 及 `tools/java-format.properties`，會先執行格式器回歸測試。首次執行會透過 Gradle 下載開發用依賴。請勿再用 Google Java Format／AOSP 格式化 Java，避免欄位對齊被移除。服務仍使用 Prettier、兩空格縮排與 100 字元行寬。格式工具只處理項目原始碼與開發工具，不處理 `references`、遊戲紀錄或編譯產物。

只檢查 Java 排版與格式器測試時，可執行：

```powershell
.\gradlew.bat -p tools/java-formatter test formatJava -PcheckFormat
```

格式檢查無法代替責任拆分與命名審查。公共傳點的世界擷取由 `BlockSnapshotCapture` 負責；網路與回應驗證由 `WarpApiClient` 負責；客戶端只協調工作並在遊戲執行緒套用結果。本地服務的 HTTP 路由、回報規則、玩家驗證、快照編碼及資料庫交易各由對應模組負責。

全項目建置會測試 Core、魔塔及公共傳點的四個 Minecraft 版本，並將可安裝的 1.0.3 JAR 放在 `out`。驗證客戶端與開發工具不會打包進安裝檔。
