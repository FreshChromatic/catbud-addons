# 公共傳點 Workers／D1 服務

## 兩服與網頁管理（1.0.3 新版）

新版以 `/v2/servers/catbud/`、`/v2/servers/catrealm/` 分開名單、傳點、預覽引用、候選、票券、资格快取及 revision。同 UUID 的兩服資料互不覆寫；共用 preview blob 按內容 hash 去重，但下載仍檢查指定伺服器的已發布引用。`/v1/` 是貓芽相容入口，不能用來讀取貓域。

部署切換前，先備份正式 D1、現有設定與私人憑證，再套用 0004–0006 與新版 Worker；0005 停用舊表的預留空間及 revision 觸發器，將記帳改為新表。舊表保留作遷移歷史，舊預覽引用仍受清理保護。已消耗但沒有新版投稿 ID 的舊票券作廢，讓客戶端重新預檢，不偽造已發布結果。正式部署狀態见 `projects/public_warps/IMPLEMENTATION.md`。

`npm run cloud -- list --server catrealm` 切換管理範圍，預設 catbud。一般投稿者只能寫入授權伺服器，投稿需審核。私人 trusted 來源的補全名稱經該服 UUID／月卡檢查後直接發布，不要求先傳送；有效觀察與合規預覽也可自動發布。補全發布不增加成功到達次數，也不偽造有效傳送紀錄。首次傳送失敗由可信來源回報後移出共用名單，補全重送不會復活；曾有效的傳點失敗後保留且可重試。沒有憑證可下載並保留本機紀錄，不能新增共用候選。規模限制兩服共用：資格每日 2600 次，預覽每日 1000 次，單份 64 KiB gzip／解碼 1 MiB。資格一小時快取、同傳點新預覽至少間隔 30 分鐘。

網頁管理獨立部署 `wrangler.admin.jsonc`，共享 D1。Cloudflare Access 限管理者郵箱，Worker 另驗 RS256 JWT、固定 issuer／aud／到期及允許名單；全部靜態資源也先驗證。寫入需同源、CSRF、requestId及適用的 expectedRevision。requestId 與操作交易同時寫入稽核，重送不再次變更或輪替密鑰；已處理回傳 409，請查操作紀錄。輪替密鑰只在第一次成功回應顯示，不寫入稽核。

所需設定：ACCESS_TEAM_DOMAIN、ACCESS_AUD、ACCESS_ALLOWED_EMAILS、ADMIN_ORIGIN、CSRF_SECRET。郵箱和秘密保存在被 Git 忽略的私人設定與 Worker secrets，請勿放進模組或公開檔。Access 沒配置時管理頁拒絕服務。管理頁提供兩服待審／已發布、預覽比較與剖切、用途與標籤、拒絕理由、回退／下架／資格警告、個別投稿者角色與範圍及服務狀態。完整 Cloudflare 帳號用量未取得時明示缺資料，不當成零用量。

新流程重點驗證可執行 `node --test test/server-isolation.test.mjs`，及 26.2 的單項 `CloudProtocolIntegrationTest`（加 `-PverifyCloudProtocol`）。舊 service.test.mjs 中直接操作舊表的流程是遷移前的回歸資料；新版 HTTP／記帳／維護以 scoped 整合場景驗證。


## 正式雲端

正式服務位址：https://api.cbwp.workers.dev

`wrangler.jsonc` 已綁定正式 D1；一般個別投稿需審核，私人 trusted 投稿通過資格與有效觀察檢查後自動發布。CLI 管理員憑證保存在本機 `.local-state/cloud/administrator.json`，Worker 使用 `ADMIN_CREDENTIAL` secret，不能與投稿憑證混用。以上私人檔案均不提交 Git。

網頁管理位址：https://control.cbwp.workers.dev 。Access 登入與登入後儲存已由管理者實際確認。「待審核」比較候選後批准／拒絕；「已發布」修改用途、下架或回退仍保留的上一版；「投稿者」授權或撤權；「操作紀錄」查管理變更。私人可信投稿正常時不需逐筆人工批准。

桌面管理清單與右側詳情各自捲動，選取後詳情從頂部顯示；窄螢幕選取後跳至詳情。每頁 25 筆，提供上一頁／下一頁與符合搜尋的總數，搜尋作用於整份該服清單，包含玩家 ID、自訂名稱及用途。頁面標題保留玩家 ID，自訂名稱另列。

在專案根目錄使用正式管理工具：

```powershell
npm run cloud --prefix services/public-warps-cloud -- status
npm run cloud --prefix services/public-warps-cloud -- list
npm run cloud --prefix services/public-warps-cloud -- grant <投稿者ID>
npm run cloud --prefix services/public-warps-cloud -- revoke <投稿者ID>
npm run cloud --prefix services/public-warps-cloud -- approve <玩家UUID>
npm run cloud --prefix services/public-warps-cloud -- reject <玩家UUID>
npm run cloud --prefix services/public-warps-cloud -- metadata <玩家UUID> ./warp-metadata.json
```

亦支援 `disable`、`restore`、`rollback` 與 `unpublish`，規則與本地管理相同。批准前會重新核對 Catbud 身份和月卡。`grant` 將投稿憑證保存到 `.local-state/cloud/<投稿者ID>-contributor-credential.json`，不在終端機顯示憑證；只有投稿憑證可交给該投稿者。

遊戲關閉後可直接設定指定實例；工具先備份原設定並保留其他欄位：

```powershell
npm run cloud --prefix services/public-warps-cloud -- configure <public-warps-service.json完整路徑> <投稿者ID>
```

其他玩家不需要管理員憑證，也不需要架設本地 Node 服務。僅下載時，設定 `serviceUrl` 為上述 HTTPS 位址、`protocol` 為 `cloud` 即可；投稿者另加入自己的 `contributorCredential`。正式與本地資料庫獨立，這次沒有將舊測試資料直接匯入正式服務。

後續公共服務部署使用本目錄的 `npm run deploy`，管理網頁使用 `npx wrangler deploy --config wrangler.admin.jsonc`。資料表更新前先備份並檢查相容性，再執行 `npx wrangler d1 migrations apply DB --remote`。不開通付費方案；免費配額仍受帳號與上游限制。CLI 使用獨立 HTTPS Bearer 管理憑證，網頁使用 Access 登入，均不提供任意 SQL 或匿名管理。

## 本地模擬

目前先在電腦上模擬 Workers 與 D1，不需要登入 Cloudflare，也不會建立雲端資源。資料保存在本目錄的 `.local-state/d1`。此資料庫與舊版 `services/public-warps` 的 JSON 資料庫分開；也與 Wrangler 預設的本地資料目錄分開。

## 啟動

在專案根目錄執行：

```powershell
npm ci --prefix services/public-warps-cloud --ignore-scripts
npm run dev --prefix services/public-warps-cloud
```

啟動會先打包本地 Worker，自動套用資料庫遷移，再提供 `http://127.0.0.1:8788`。打包輸出中的 `deploy --dry-run` 只作建置檢查，不會部署。服務只綁定本機，Ctrl+C 關閉。每分鐘處理最多三個到期或待審核傳點；正常查核成功後，至少一小時才再次輪巡。

預設每日資格查詢上限 2,600 次，適合先驗證約 100 個傳點的每小時更新；新增、失敗重試也會消耗配額。5,000 個是儲存數量上限，並非每小時更新的已驗收容量。Catbud API 故障、格式錯誤或身份不一致會保留既有資格與實際查核時間。

## 模組設定

關閉遊戲後，在該遊戲實例的 `config/catbud/public-warps-service.json` 設定：

```json
{
  "serviceUrl": "http://127.0.0.1:8788",
  "protocol": "cloud",
  "traceTeleport": true
}
```

沒有投稿憑證仍可下載公開清單、保存自己的補全名稱和預覽。要測試投稿，先在另一個終端機執行：

```powershell
npm run admin --prefix services/public-warps-cloud -- grant local-player
```

工具會把憑證保存到 `.local-state/credentials/local-player-contributor-credential.json`，不在終端機顯示內容。把檔案內的 `contributorCredential` 欄位加入上述遊戲設定，再重新啟動遊戲。憑證可授予投稿權限，請勿放入 Git 或公開發布；再次 grant 同一 ID 會輪替憑證，舊憑證立即失效。

## 管理

以下命令同樣在專案根目錄執行，UUID 須替換成實際玩家 UUID：

```powershell
npm run admin --prefix services/public-warps-cloud -- list
npm run admin --prefix services/public-warps-cloud -- approve <UUID>
npm run admin --prefix services/public-warps-cloud -- reject <UUID>
npm run admin --prefix services/public-warps-cloud -- disable <UUID>
npm run admin --prefix services/public-warps-cloud -- restore <UUID>
npm run admin --prefix services/public-warps-cloud -- rollback <UUID>
npm run admin --prefix services/public-warps-cloud -- unpublish <UUID>
npm run admin --prefix services/public-warps-cloud -- revoke local-player
```

核准會先向 Catbud 重新核對身份與月卡；查核失败或沒有月卡就不能核准。首次名稱發現及預覽更新在核准前不會出現在公開下載中。恢復可用性是管理員對傳點存在性的人工判斷，不會替玩家設定月卡，也不會自動核准新預覽。預覽回退只適用仍保留且未過期的上一版。下架會停止公開清單與預覽下載，資料本身仍保留。撤銷投稿權限會立即釋放未完成上傳的空間預留。

介面中曾有效的失效傳點保留並顯示原因，仍允許再次嘗試傳送，不要求補全存在或月卡資格有效。此操作不會事先清除失效標記，仍須真正成功到達才更新本地狀態。

## 備份與復原

備份會使用 SQLite 的線上備份機制，可在本地服務運行時建立一致的資料庫副本，包括正式傳點、候選預覽、上一版預覽、配額、審核紀錄及投稿權限狀態。原始投稿憑證檔案不在資料庫內，請另外保管。

在專案根目錄執行，目的地必須是尚不存在的目錄：

```powershell
npm run admin --prefix services/public-warps-cloud -- backup ./public-warps-backup
```

工具會輸出備份檔案路徑。復原會先檢查資料庫完整性、外鍵及空間計數，只允許寫入新的資料目錄：

```powershell
npm run admin --prefix services/public-warps-cloud -- recover <備份檔案路徑> ./public-warps-recovered
npm run admin --prefix services/public-warps-cloud -- list --state ./public-warps-recovered
npm run admin --prefix services/public-warps-cloud -- serve --state ./public-warps-recovered
```

先停止原本的本地服務，再啟動復原服務，避免同時使用 8788 連接埠。其他管理命令同樣可附上 `--state <資料目錄>`。復原不會覆蓋原本的資料庫，也不會重新授權已撤銷的投稿者。備份包含私人候選資料與投稿憑證雜湊，請放在受保護的地方，不要提交到 Git。

## 自訂名稱、用途說明與標籤

先建立 JSON 檔案，例如 `warp-metadata.json`：

```json
{
  "displayName": "公共農場",
  "description": "提供小麥與南瓜",
  "tags": ["農場", "食物"]
}
```

再執行：

```powershell
npm run admin --prefix services/public-warps-cloud -- metadata <UUID> ./warp-metadata.json
```

這項操作由管理員執行，會更新公開清單修訂及審核紀錄；不會改動玩家 ID、UUID、月卡資格或傳送可用性。名稱最多 64 字元、說明最多 512 字元、標籤最多八個且每個最多 24 字元；相同標籤會合併。填入 `null` 可清除自訂名稱或說明，空陣列可清除標籤。

## 共享實體外觀

模組與服務端共用 shared-appearance.json 規則，逐層保留外觀需要的資料，而非直接上傳整個裝備或物品 NBT。可保留物品 ID、數量、染色、自訂模型識別、盔甲飾紋、附魔亮光、姿態、村民類型及有限展示轉換。自訂名稱、說明、書本、容器內容、擁有者及未知巢狀欄位會排除。玩家模型與文字展示實體仍不共用；本地完整快照不會被這個共享過濾覆寫。

附魔資料只用來推導是否有亮光，不保存原始附魔內容。過長、過深、重複欄位或格式錯誤的共享標籤會被拒絕；數值與列表大小也有限制。識別字串是外觀資源識別用途，不是物品名稱或自由文字。

## 清單同步

模組每五分鐘更新共享清單，手動重新整理最少間隔 30 秒。首次與每小時會取得完整清單，其他更新使用有修訂編號的增量清單。完整清單與變更紀錄每頁最多 100 筆；分頁期間修訂變動、變更紀錄超過保留期，或增量過多時，會略過快取重新抓取完整清單，不把半份結果寫進本地。

公開清單與變更 API 使用 Workers Cache API，保留最多 60 秒。指令補全仍向遊戲伺服器取得，不受這個共享清單快取影響。

共享下架通知會移除純下載的傳點與下載預覽。玩家曾從補全發現的名稱、成功到達紀錄及本地擷取的預覽會保留；該筆的共享名稱、用途與資格資訊會解除，不把管理員下架等同於遊戲內取消公共傳點。每小時的完整讀取會更新實際月卡查核時間，不會以空增量假裝完成新的資格查核。

## 容量與突發流量保護

服務會從 D1 的 `meta.size_after` 讀取整個資料庫大小，包含資料表與索引；另保留既有 BLOB／上傳票據的原子容量帳本。達 350,000,000 bytes 時暫停新投稿與新增投稿憑證，審核已有內容時不再新增上一版預覽。達 400,000,000 bytes 時另暫停背景資格更新、批准及中繼資料更新。既有公開清單與預覽下載仍可使用；清理、撤權與下架操作仍可進行。資格更新暫停不會把玩家誤標為沒有月卡。大小量測缺失時停止新增投稿，不猜測剩餘容量。[D1 回傳資料說明](https://developers.cloudflare.com/d1/worker-api/return-object/)

已完成的上傳票據重試仍會回傳原結果，不因容量限制重新寫入。刪除後會重新量測大小，不能假設刪除立即釋放正式 D1 計量空間。350／400 MB 是保護線，量測與後續操作之間仍可能有併發；它不取代原子 BLOB 預留，也不是物理檔案大小不會越線的證明。完整真實負載與每日 rows_read／rows_written 成本仍需部署前驗收。

查看本地容量：

```powershell
npm run admin --prefix services/public-warps-cloud -- status
```

Worker 在資料庫及快取處理前先通過 Rate Limiting bindings：每地點全部請求初始每分鐘 6,000 次，同來源公开讀取每分鐘 600 次、投稿每分鐘 120 次。來源只作寬鬆突發輔助，使用有限長度位址的雜湊；不保存原始 IP 或把它當投稿身份。缺少 binding 或保護服務故障時回傳 503，限流回傳 429，均附 Retry-After。本地使用真實模擬 binding；正式部署前須確認三個 namespace_id 未與帳號其他服務共用。

快取命中仍經過這層限制。此 binding 按 Cloudflare 地點生效並且最終一致，不能保證全域精確次數，也不能保證拒絕請求不消耗免費 Worker 呼叫額度。原有每日憑證、投稿、UUID 冷卻及上游配額仍在 D1 控制。[Rate Limiting 限制說明](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)

## 每日資料庫用量報表

各 HTTP 流程、背景資格查核及本地管理操作，會輸出一筆 `d1_usage` JSON 紀錄，累加 D1 回傳的實際 `rows_read`、`rows_written` 及最大 `size_after`。報表按 UTC 日期分組，包含索引及觸發器在 D1 回傳的成本；不記錄 SQL、玩家名稱、UUID、憑證或 IP，也不為匿名讀取新增資料庫計數寫入。查詢失敗或缺少計量時會保留未知數量。

在專案根目錄將已保存的 JSONL 紀錄整理成報表：

```powershell
node services/public-warps-cloud/tools/usage-report.mjs <紀錄檔完整路徑>
```

工具支援逐行 JSON 紀錄，以及 Wrangler tail JSON 事件內的日誌訊息；相同紀錄 ID 重複出現只算一次。輸入最多 128 MiB、每行最多 64 KiB，最多整理 32 天／200,000 筆紀錄。沒有計量紀錄時會拒絕產生零用量報表。每日觀測到 50,000 行寫入或 2,500,000 行讀取時，`warning` 為 true；查詢成本不明時，`incomplete` 為 true。

`scope: observed_logs_only` 表示只計算輸入紀錄。紀錄遺失或採樣、啟動遷移、其他工具與其他服務的查詢不在總額內；快取命中與限流拒絕也不是這份報表的 Worker 呼叫計數。即使 `incomplete` 為 false，也不證明收集了全部紀錄。警戒標記目前供管理者判讀，尚未連接全域自動停寫；正式雲端日誌保存、帳號指標核對及警戒後收縮策略仍須驗收。

本地整合測試會產生 `services/public-warps-cloud/build/local-d1-usage.jsonl`。一個 UUID 的發現、資格查核、預檢、上傳、審核、清單與預覽下載，合計 55 次查詢、121 行讀取、62 行寫入；其中公開清單與預覽下載沒有寫入。這是小型本地測試成本，不代表大量傳點或正式雲端的成本。

上一版預覽的七天保留期只在真正保留新歷史版本時重新計算。批准名稱資料、重複預覽，或容量受限而略過新歷史版本時，既有上一版維持原本期限，到期由背景清理移除；不會因其他審核一直延長。

## 驗證與待完成項目

本地模組串接測試不需要 Cloudflare 登入：

```powershell
npm run bundle --prefix services/public-warps-cloud
./gradlew.bat :projects:public_warps:26.2:test :projects:public_warps:1.21.11:test -PverifyCloudProtocol
```

這項測試啟動獨立暫存 D1，使用正式 Java 用戶端驗證投稿、審核、下載及同步協定，包括 50³ 預覽下載及月卡失效後保留传點和預覽。介面仍允許重試，由遊戲伺服器決定能否傳送。資格回應由資料模擬，不能取代實服驗收；一般建置排除此串接測試。

本地單份預覽上限為壓縮 4 MiB、解壓 16 MiB；共用上限維持壓縮 64 KiB、解壓 1 MiB。本地可保存超過共用上限的完整 50³ 預覽。雲端模式的待投稿資料先移除不共用的實體及私有外觀資料；超出共用限制時僅回報成功到達。本地預覽按最近使用順序保留最多 64 份及 24 MiB 編碼資料，待投稿預覽總額最多 4 MiB；總額超出時保留到達回報並移除較舊的待投稿圖片。帳號／伺服器快取檔上限維持 32 MiB。

遊戲內解碼資料另採 48 MiB 的估算快取預算，渲染網格連同其保留的快照採 192 MiB，畫面色彩／深度目標採 96 MiB。各層最多 64 項，超額會淘汰最近最少使用的預覽；網格淘汰時一併解除實體模型與對應畫面目標。畫面目標在該幀結束後才釋放，以免破壞已提交的畫面。這些是物件／像素估算，不是實際 JVM 或顯示卡記憶體保證；單一超額場景仍暫留，避免每幀重建完整模型，因此極複雜模型需要另外壓力驗收。

首次預覽的方塊模型準備、面排序、實體準備及材質分組已拆成多幀，每個介面共用約 2 毫秒的合作式處理預算。右側預覽優先開始準備，未完成時顯示「正在準備預覽…」；離開畫面的工作會取消。一個 125,000 格／375,000 面／256 個實體顯示狀態的原生測試分 70 個遊戲 tick 完成，最大一次處理約 2.24 毫秒。這不是整幀耗時的硬上限：單個複雜模型、記憶體回收及顯示卡繪製仍可能超時，密集場景的完整效能驗收尚未完成。

```powershell
npm test --prefix services/public-warps-cloud
```

測試使用實際本地 D1，涵蓋審核隱藏、身份及月卡查核、併發預留、上傳去重、撤權、交易失敗回滾、預覽回退、下架和解壓大小限制。新共享預覽為 50×50×50；64 KiB 壓縮及 1 MiB 解壓限制不會因範圍加大而放寬。超過共享上限會保留本地預覽並停止該筆上傳重試。

正式雲端已部署，`POSTING_ENABLED=true`；匿名用戶仍無法投稿。仍需實際遊戲驗收和較大規模負載觀測，不能把本地測試成本視為正式帳號用量保證。

## 玩家編輯自己的傳點

遊戲內選取自己的既有共用傳點後，可用「編輯我的傳點」修改自訂名稱（64 字）與多行用途說明（512 字）。原玩家 ID、標籤、預覽及可用狀態保留。入口須匹配目前帳號 UUID 與所在伺服器補全；真正寫入授權來自官方 V2 帳號憑證與一次性挑戰簽名，不能靠相同 ID 或自報 UUID 冒用。

獨立 owner 路由位於 `/v2/servers/<server>/owner/`：POST challenge／verify／metadata、GET warp。身份憑證、UUID 與伺服器綁定；儲存核對版本並重新檢查月卡，錯誤保留草稿。owner 憑證不提供投稿者、管理者或其他傳點權限。密碼、官方登入 Token、帳號私鑰都不傳給本服務。

官方信任公鑰固定在 `src/official-certificate-keys.json`。官方輪替後管理者可執行 `node tools/refresh-official-keys.mjs`，核對官方來源及輸出的雜湊，再部署 Worker；一般玩家不能更新信任公鑰，驗證失敗不降級。維持免費 Workers＋D1，不需要新的付費平台。真正正版帳號的首次正式儲存驗收仍待完成。
