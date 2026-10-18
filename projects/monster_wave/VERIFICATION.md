# 怪物浪潮驗證結果

驗證日期：2026-10-18。範圍為本機編譯、行為測試、客戶端啟動及安裝檔檢查；未連線實際怪物浪潮伺服器。

## 停用副本事件檔案紀錄

正式啟動改用 DisabledRunSink，不建立寫入執行緒、不讀取歷史檔案，移除設定頁的檔案與紀錄欄位，以及僅供紀錄使用的附近怪物取樣。
26.3 的 HealthProtectionTest 與 MonsterWaveSessionTest 共 36 個案例通過；四版本編譯打包通過。
本次未再次啟動遊戲或連線實服；先前的客戶端驗證結果僅適用於當時版本。
建置證據：`versions/26.3/build/disable-recording-build.log`。

## 扣血預警與清理畫面的先前驗證

依使用者要求限制測試範圍：新增 4 個針對性案例及 1 個工作階段整合案例，並僅在 26.3 執行 HealthProtectionTest 與既有 MonsterWaveSessionTest，共 36 個案例通過；沒有重跑四版本完整測試。
涵蓋 20 → 14 → 8 提前斷線、回血不抵銷已發生的扣血、1 秒／5 秒視窗到期、4 點保底、重置／無效輸入、提前斷線後恢復，以及等待背景工作時的刷新。

26.3 客戶端的 43 項檢查通過，確認新 Mixin 可載入、清理前呈現斷線畫面、等待 Future 時刷新 UI，以及清理期間停用按鈕、完成後恢復。清理展示驗證在約 150 毫秒的延遲工作期間刷新 8 幀；這是受控測試，並非實服延遲數據。
四版本僅執行編譯及打包，產物輸出至 out/。
附近生物觀測取樣僅作紀錄附加欄位，最後補入後以四版本編譯檢查，不為此再次啟動完整客戶端驗證；未連線實服量測實際怪物數量。

本次證據：

- `versions/26.3/build/burst-tests.log`：36 個針對性與工作階段案例通過。
- `versions/26.3/build/test-results/test/TEST-*.xml`：本次所選測試結果。
- `versions/26.3/build/final-cleanup-client.log` 與 `build/verification-monster-wave/monster-wave-verification.txt`：最新清理畫面驗證。
- `versions/26.3/build/burst-assemble.log`：四版本打包。

查閱的原版來源位於 `C:/Users/User/Desktop/Cinematic/references/MINECRAFT/minecraft_client_26.2/` 與 `minecraft_client_26.3/`：

- `net/minecraft/client/Minecraft.java`：資源包與連線清理早於 setScreenAndShow；之後才卸載各引擎世界。
- `net/minecraft/client/resources/server/DownloadedPackSource.java`：斷線時 popAll 資源包。
- `net/minecraft/client/renderer/SectionOcclusionGraph.java`：waitAndReset 中 Future.get 同步等待。
- `net/minecraft/client/renderer/LevelRenderer.java`：釋放渲染資料時等待並重設可見性圖；另有 GPU 緩衝區清理。

修改只作用於本模組觸發的斷線：提前呈現畫面，並在無世界、確定卸載且位於主執行緒的可見性等待中刷新 UI。保留完成工作後才重設資料的順序，不將 GPU 清理移到背景執行緒，也不跳過資源包清理。其他耗時仍需實服紀錄定位，沒有宣稱完全消除所有卡頓。

## 先前版本的完整驗證紀錄

下表是加入本次扣血預警及清理畫面修改之前的完整驗證，不能代替本次變更在其他版本的運行驗證。

| Minecraft | 行為測試 | 客戶端檢查 | 安裝檔 |
| --- | --- | --- | --- |
| 1.21.11 | 40 通過，0 失敗 | 39 項通過 | 已產出、已重映射 |
| 26.1.2 | 40 通過，0 失敗 | 39 項通過 | 已產出 |
| 26.2 | 40 通過，0 失敗 | 39 項通過 | 已產出 |
| 26.3 | 40 通過，0 失敗 | 39 項通過 | 已產出 |

## 已驗證行為

- 排隊、準備、正式運行與離場的狀態轉移；無 READY 訊號但 Bossbar 消失後確認傳送的相容路徑。
- Bossbar 替換、傳送封包先到、世界暫缺、排隊 3 秒／準備 15 秒寬限與未進場取消。
- 其他副本、通用準備訊息與單純 custom 世界不獨立觸發。
- 生命值 4.0 不觸發，3.5、3.0、0.0 立即觸發；已低血量進場、強制運行、一次性觸發與結束原因保留。
- 非運行、停用、手動停止與單人世界不斷線；重新連線恢復自動模式。
- READY_START／READY_EXIT 辨識；準備退出、Bossbar 消失、同維度傳送及長時間運行不停止保護。
- 單調時鐘的耗時、分離排隊／正式運行時間、重複文字保留及事件順序。
- UTF-8 JSONL、UTC 時間、檔案分段、終止摘要、事件佇列溢位與缺漏數量。
- 磁碟寫入失敗時保護仍能觸發；無結尾或損毀紀錄不推造結束時間。
- 運行中斷線後，同帳號重加入同伺服器的 custom 世界恢復保護；暫時缺少世界、重複離線 tick 不清除待恢復狀態。
- 重加入仍低血量可再次斷線，每次連線只觸發一次；恢復觀測記錄前段 runId 與離線時長。
- 其他維度、伺服器、帳號及單人世界不恢復；尚未 ACTIVE 或已離場的回合不建立恢復候選；明確停止、重設、停用或關閉遊戲會清除候選。

## 客戶端與產物檢查

四版本均啟動獨立的 Fabric 客戶端，確認十個遊戲封包與兩個斷線清理 Mixin 攔截點已套用，以及 Core 設定與語言資源載入。
使用 Minecraft 實際 Bossbar 封包驗證排隊／準備、名稱、進度、樣式與旗標更新，輸出 Bossbar、文字、標題時間及低血量結束摘要。
以 Netty EmbeddedChannel 建立測試連線，確認正式斷線轉接呼叫後連線立即關閉；此測試不連線實服。
另以延遲完成的 close promise 驗證非同步關閉：呼叫返回時連線仍在等待關閉、斷線原因已保存、autoRead 已停用；釋放延遲後才完成關閉。
測試有獨立的限時解鎖機制，若重新引入同步等待，會解除等待後報失敗，避免驗證客戶端永久卡住。
另確認斷線後 custom 恢復、不重新排隊即可保護、恢復後低血量觸發、前後紀錄關聯，以及非 custom 重加入清除候選。

四個安裝 JAR 均具有 `catbud_monster_wave` 模組資訊，內嵌一份對應版本 Core，且不含驗證用類別。
1.21.11 產物的 Mixin 目標已映射為 intermediary 名稱。正式產物位於根目錄 `out/`。
Java 已透過 `tools/format-code.ps1` 的 Eclipse JDT 設定格式化；保留原有其他模組的未提交內容。
專案新增來源後觸及 Windows 命令列長度限制，格式化任務改用以工作區為基準的相對來源路徑，並已重新驗證。

原始證據位於各版本的：

- `build/test-results/test/TEST-*.xml`
- `build/verification-monster-wave/monster-wave-verification.txt`
- `build/verification-monster-wave/logs/catbud/monster_wave/*.jsonl`
- `build/client-verification.log`

四版本建置彙總：`versions/26.3/build/build-verification.log`。

## 待實服觀測

仍需確認怪物浪潮實際使用的維度、封包元件格式，以及正常離場是否離開 `minecraft:custom`。
`ready_start`／`ready_exit` 已依使用者提供的 Bossbar 來源實作，但尚不把它們出現的時間當成正式開始／正常結束時間。
若離場仍在同維度，會持續保護並保存訊號，待實測建立可靠的結束規則。
低血量保護依客戶端收到的生命值更新反應，實測報告不代表已證明能避免伺服器判定的死亡。
非同步關閉驗證證明不等待網路 close promise，尚未量測使用者實服的世界清理時間；原版清理和畫面準備仍可能造成延遲。`logs/latest.log` 的分段計時供實服定位使用。
