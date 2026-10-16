# 公共傳點管理網頁

這個管理 Worker 與公共下載 Worker 使用同一個 D1，但使用不同入口。所有頁面、靜態檔案、候選預覽及管理 API 都先經過 AccessVerifier；靜態 assets 設定 run_worker_first=true，不直接繞過登入。

目前已完成本地程式與打包，尚未部署。正式啟用前仍須完成 namespace 的 maintenance／v1 相容切換及 Cloudflare Access 設定。

## 登入設定

`wrangler.admin.jsonc` 是獨立管理 Worker 設定，不會替公共下載網址加上登入限制。正式部署需設定：

- ACCESS_TEAM_DOMAIN：實際 Cloudflare Access 團隊網址，例如 `https://你的團隊.cloudflareaccess.com`。
- ACCESS_AUD：管理 Access application 的 audience tag。
- ACCESS_ALLOWED_EMAILS：管理者郵箱允許名單，以逗號分隔，透過私有設定或 Worker secret 保存。
- CSRF_SECRET：獨立的 32 bytes 隨機 hex secret，不能放進靜態網頁或公開 JAR。
- ADMIN_ORIGIN：實際管理網頁的完整 origin，沒有結尾斜線。

管理郵箱已保存在 `.local-state/cloud/access-settings.json`，不寫入公開配置。沒有完整登入設定時請求會被拒絕。正式 Access application 必須保護整个管理網址；preview_urls 已停用。即使有人直接請求 Worker 或偽造 header，仍須通過 RS256 簽章、issuer、audience、到期與郵箱驗證。[Cloudflare JWT 驗證說明](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/)

## 網頁操作

登入後預設顯示待審核，伺服器選單分別查閱貓芽及貓域。選取一笔候選後比較投稿類型、資格檢查時間、正式版本與提案版本。僅有名稱發現不構成可發布的有效傳點證據。

預覽使用方塊分類上色，拖曳旋轉、滾輪縮放，剖切高度可看內部；實體使用位置標記。它不是 Minecraft 的完整材質渲染。超過表面顯示上限時有提示，完整快照可下載核對。預覽在背景執行緒解碼，載入完成前與載入失敗時不可批准。

批准只作用於目前選定的 submissionId。資料已變更、資格不符、候選到期或已處理時停止操作，不自動批准另一筆資料。拒絕須提供原因。

已發布頁可修改名稱／用途／標籤、標記可用狀態、下架及回退保留中的上一版預覽。可用狀態修改不增加成功到達次數。投稿者頁可指定角色、啟用狀態與兩服授權範圍；新建或更換密鑰只顯示一次，不可由網頁讀回舊密鑰。

## 本地验证範圍

重點測試使用 `test/access-verifier.test.mjs` 與 `test/server-isolation.test.mjs`。管理 Worker 可透過 Wrangler 的 dry-run 打包確認，不部署。

2026-10-12 已完成一次瀏覽器檢查，使用僅在本機的模擬資料，確認提案預覽、批准按鈕載入狀態、切換伺服器與窄螢幕排版。這不等於正式 Access 登入或線上審核驗收。
