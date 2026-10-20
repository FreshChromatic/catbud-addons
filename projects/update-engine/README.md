# 更新與通知

更新核心為不依賴 Minecraft 的 Java 21 程式庫。Core 在四個遊戲版本使用同一套版本、簽章、盤點、下載及通知規則；獨立安裝 JAR 只包含 JDK 相依的 `update.install` package。

程式支援 Core、Magic Tower、公共傳點、怪物波次，目標遊戲為 1.21.11、26.1.2、26.2、26.3。實際發版模組由 `release/modules.json` 明列，目前只發布 Core、Magic Tower、公共傳點，共十二個可安裝 JAR。需要發布怪物波次時再將 `monster_wave` 加入清單。

## 玩家操作

按 Z 開啟 Core 設定頁，或由 Mod Menu 開啟。Core 的「更新」顯示檢查與安裝狀態、目前／新版版本、大小、更新摘要及下載入口；「通知」顯示符合模組、遊戲版本與目前伺服器的公告。

自動檢查在啟動後延遲 15–45 秒進行，之後約每 30 分鐘一次。可以關閉自動檢查，或手動檢查（最少間隔 30 秒）。同版本提醒、略過版本、明天提醒及公告已讀均保存到 `config/catbud/notifications.json`。提醒合併成摘要，不逐則刷聊天。

更新依完整 SemVer 及同版本修訂判斷；不降級。相同版本會比較 SHA-256：內容相同不下載，內容不同必須使用更高修訂。更新頁與提醒顯示例如 `1.0.2 (r2)`；略過或延後 r2 不影響 r3。已安裝修訂由工具產生的 JAR 檔名識別，請保留檔名。新打包方式的附加模組不再內嵌 Core，玩家必須另外安裝一份共用 Core。更新以實際安裝的頂層 JAR 為單位，不擅自安裝未使用的附加模組。額外模組宣告的依賴與衝突也會透過 Fabric 的版本判斷檢查。

驗證器仍接受舊版內嵌 Core 的簽署清單。從舊包裝轉換時，先手動安裝相容的獨立 Core；只有內嵌 Core 時，更新計畫會拒絕移除它，避免更新後缺少依賴。尚未支援新清單格式的舊 Core，須先手動更新。

## 啟動器防護

一鍵安裝只在本次啟動通過 launcher guard 時啟用。沒有防護仍可查看版本、閱讀公告及到 GitHub 手動下載。

1. 先手動安裝包含更新器的獨立 Core 及需要的附加模組，啟動一次。
2. 到「更新」按「複製啟動前指令」。Core 已把受信任的防護程式匯出到該實例的 `config/catbud/update-launcher.jar`。
3. 將指令貼到啟動器對該實例提供的「啟動前指令」設定。啟動器必須等待指令完成，且指令失敗時停止啟動。若啟動器不支援這個契約，使用手動下載。
4. 關閉遊戲，再由啟動器啟動一次。每次啟動都須保留此設定，同一遊戲實例不要同時執行多個 Minecraft。
5. 在更新頁下載，驗證完成後選擇「關閉遊戲並安裝」。下次啟動前防護會等待正在執行的安裝，必要時復原中斷的批次，再允許 Fabric 載入模組。

防護每次成功產生短效、單次使用的啟動證明；Core 消耗它才啟用一鍵安裝，不只憑一個長期設定開關猜測啟動器行為。不能保證外部程式無視防護直接啟動時的安全，因此這種方式不屬於支援的自動安裝流程。

[Prism 的官方自訂指令說明](https://www.prismlauncher.org/wiki/help-pages/custom-commands/)描述了啟動前指令執行在遊戲實例啟動之前。本專案已測試獨立防護程序及四版遊戲的啟動流程，不宣稱已在每款第三方啟動器實測。

## 下載、安裝與復原

- 版本清單必須通過 Ed25519 簽章，下載 URL 限固定 GitHub 儲存庫，重新導向逐跳檢查 HTTPS 主機。
- 單檔最多 64 MiB、整批最多 256 MiB。下載串流、實際大小、SHA-256、頂層與內嵌 mod metadata、Java／Loader／Minecraft 及依賴全部檢查。
- 下載前、下載完成後、遊戲退出後各重查核准狀態；撤回或離線時不動舊檔。
- helper 在遊戲退出後備份全部舊檔，再安裝全部新檔。中途失敗回復整批；Windows 檔案鎖最多重試約 60 秒。
- helper readiness 握手通過後才顯示待安裝；下次 Core 啟動核對載入來源和檔案後才顯示成功。
- 啟動防護先取得更新鎖；中斷批次在 Fabric 解析 JAR 前復原。不以 Core 初始化後檢查鎖代替這個步驟。
- `config/catbud/updates/<batchId>/` 保留 journal、結果、helper、日誌與 `RECOVERY.txt`。手動復原前先關閉所有使用該實例的 Minecraft，依該批次文件使用獨立 helper，不需要能載入 Core。
- mods 裡的備份放於 `.catbud-update-<batchId>/`，使用非 `.jar` 副檔名。成功啟動確認後，舊批次超過七天才清理；最近一批備份繼續保留。失敗批次及含不明檔案的目錄不自動刪除。

## 發版

`release/keys.json` 是 Core 與兩個 Worker 共用的公開信任金鑰。正式私鑰只在被 Git 忽略的 `.local-state/release-signing/private-key.pkcs8`，已限制本機檔案權限；需由擁有者另行安全備份。不要把它放進 Release、Worker、JAR 或 Git。

`release/notes.json` 為各模組摘要，發版前須審閱。原始碼確認並提交後，在儲存庫根目錄執行：

```powershell
.\tools\release\prepare.ps1
```

工具拒絕未提交的工作樹，先執行完整 Java 建置與測試，再依明列的模組 × 遊戲版本挑選產物。它不會把 `out/` 的舊版本或 sources/dev JAR 全部帶入。manifest 會綁定提交、版本、相容條件、每個檔案的大小與 SHA-256，簽署後再以公開金鑰驗簽。

預設產物位於 `out/releases/v<version>/`。目錄必須尚不存在；可用 `-OutputDirectory` 明確指定其他新目錄。新建版本的 `mod_version` 與 `core_version` 必須一致。工具只準備檔案，不會提交 Git、推送 tag 或發布 GitHub Release。

同版本修正使用遞增修訂，不修改 `mod_version` 或 `core_version`。原始發布視為 r1，標籤維持 `v1.0.2`；再次發布執行：

```powershell
.\tools\release\prepare.ps1 -Revision 2
```

這會產生 `out/releases/v1.0.2-r2/`，對應新的 `v1.0.2-r2` GitHub Release。JAR 名稱加上 `-r2`，內部模組版本仍為 `1.0.2`。下一次使用 `-Revision 3`，依此類推。修訂由簽署清單的 tag、releaseId、下載 URL 及檔名共同綁定；每個修訂都需重新簽署、匯入與核准。未變更的模組仍包含在完整發布清單中，但客戶端不會重複下載相同內容。更新器第一次支援修訂時，玩家須先手動安裝新版 Core，或先透過一般版本升級取得支援；舊 Core 無法解讀修訂標籤。

建立對應提交的 `v<version>`（修訂則為 `v<version>-rN`）GitHub 草稿，將該目錄的所有 JAR、`release-manifest.json`、`release-manifest.sig` 上傳並審閱後發布。目前為十二個 JAR 加兩份清單，共十四個附件。不要手動移除仍被簽署清單列出的附件；改變發版模組時必須重新產生並簽署清單。建議啟用 GitHub immutable releases；已發布標籤的產物不能覆寫，修正時建立下一個修訂。

目前 GitHub 舊 Release 沒有簽署清單，不能直接匯入自動安裝。第一個含更新器的版本需要先由玩家手動安裝；之後才能使用這套更新流程。

## Cloudflare

服務原始碼在 `services/public-warps-cloud/src/addons/`，新增資料表由 `0008_addon_updates.sql` 建立。沿用既有 `api`、`control` 兩個 Worker 與 D1 binding，領域資料與傳點 revision、資格查核配額分開。

公開路由：

```text
GET /addons/v1/catalog?minecraft=26.2&channel=stable
GET /addons/v1/notices?minecraft=26.2&server=catbud
GET /addons/v1/status
```

公開目錄只讀已核准快照，不因玩家查詢抓取 GitHub。不傳玩家名稱、UUID 或投稿憑證。目錄以遊戲版本及 stable 指標選取；沒有相容版本、服務故障及已是最新版是不同狀態。

`/addons.html` 是管理入口，沿用 Access、CSRF、同源、requestId、expectedRevision 及稽核。匯入後核對簽章、附件與 tag 提交，管理者再核准對外推薦。每個遊戲版本均須包含 Core、Magic Tower、公共傳點，以及該版選定的其他模組，才可核准。多版本指標與稽核在同一 D1 交易生效；重送回傳同一結果，競爭修訂不會半批發布。公告需先儲存草稿再發布，已發布內容先撤回才能編輯。

同版本修訂在管理頁輸入 `v1.0.2-r2` 匯入，確認後核准。舊發布與新修訂各自保留紀錄，核准前不改變客戶端目錄。推薦標頭與快取識別包含完整修訂標籤，下載前後及安裝前的核准重查也使用同一標籤。此功能沿用既有資料表，無須新增 migration；需部署更新後的 api、control 及管理網頁才會在正式環境生效。

未被任何遊戲版本推薦的版本可以在詳情頂端「刪除版本」，經確認後從管理清單移除。仍被推薦時須先撤回；刪除保留操作紀錄，不刪除 GitHub Release 或玩家檔案。GitHub 來源仍完整時可重新匯入。刪除沿用修訂衝突檢查與 requestId 重送保護。

公開快取最多 60 秒，下載及安裝前的核准重查略過快取。每日檢查 GitHub 已核准產物；異常會停止提供安裝核准。新增 ADDON_BURST binding 限制更新端點流量，不共享傳點上游資格配額。

正式上線順序：先備份 D1，套用新增 migration，部署 api（含 ADDON_BURST）及 control，再在管理頁匯入並核准已公開的簽署 Release。不要只部署 Worker 而漏掉 migration。實作與本機驗證不等於已完成正式部署。

## 驗證

```powershell
.\gradlew.bat :update-engine:test
.\gradlew.bat build releaseJars
.\gradlew.bat :projects:catbud_core:26.2:runClient -PverifyUpdates
```

最後一項可替換為其他三個 Minecraft 版本。它使用獨立 `build/verification-updates` 實例及模擬 UI 資料，不安裝更新到玩家的遊戲實例。結果為該目錄的 `updates-verification.txt` 和 screenshots。UI fixture 不代替檔案安裝／簽章／下載的獨立測試。

雲端主要回歸場景：

```powershell
cd services/public-warps-cloud
node --test test/addon-updates.test.mjs test/burst-guard.test.mjs test/access-verifier.test.mjs test/server-isolation.test.mjs test/owner-editing.test.mjs test/trusted-discovery.test.mjs
node tools/addon-verification.mjs
```

後者建立僅綁 127.0.0.1:8791 的隔離管理頁、臨時 D1 與測試簽章，不連線正式 Cloudflare 或發布真正 GitHub Release。

完整雲端 `npm test` 尚有十九項舊傳點測試失敗，已在原始提交的獨立副本重現相同失敗；不能將完整雲端測試宣稱為全部通過。更新／公告與目前伺服器隔離、Access、投稿及擁有者編輯的相關回歸另行執行並記錄。
