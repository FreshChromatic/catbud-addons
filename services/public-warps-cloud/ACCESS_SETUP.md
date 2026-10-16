# 管理登入設定

管理網頁已部署至 https://control.cbwp.workers.dev 。Access team domain 與 application audience 已設定。2026-10-13 使用者將原有 Access 應用的 hostname 更新至此新網域；未登入請求已確認跳轉至原有 Access 組織。網域更新後需在瀏覽器重新登入。

先前 Wrangler 登入憑證能部署 Workers 與 D1，但建立 Access application 曾兩次收到 403，因此 Access 應用由使用者自行建立。若日後由助手建立或修改 Access 應用，仍需下方指定的 Access 管理權限。

## 讓助手完成設定

1. 在 Cloudflare Zero Trust 確認已啟用免費方案。
2. 開啟 https://dash.cloudflare.com/profile/api-tokens ，建立自訂 API Token，帳號資源只選本專案的 Cloudflare 帳號。
3. 權限加入 `Access: Apps and Policies` 的 Edit，以及 `Access: Organizations, Identity Providers, and Groups` 的 Read。前者用於建立管理網站的應用與允許郵箱政策，後者用於讀取登入組織的 team domain。
4. 將 Token 存成本機 `.local-state/cloud/access-api-token.txt`，檔案只放 Token 本身。此資料夾已被 Git 忽略。不要把 Token 貼到聊天、公開文件或模組內。
5. 回覆「已存」，再由助手建立限定管理網站的應用與郵箱政策，配置 team domain／audience，並完成登入驗收。

## 自行設定

1. 登入 Cloudflare，進入 Zero Trust。尚未啟用時，建立 Team name 並選擇 Free 方案；官方說明指出免費方案的首次啟用仍可能要求付款資料，但選 Free 不收費。
2. 進入 `Access controls → Applications → Create new application`，選 `Self-hosted and private`（舊版介面可能顯示 `Access → Applications → Add an application → Self-hosted`）。
3. 名稱填 `Catbud 公共傳點管理`；新增 public hostname，完整填入 `control.cbwp.workers.dev`，不加 `https://`。若網域下拉選單沒有 workers.dev，使用 `Switch to custom input`。Path 留空以涵蓋整個管理網站，Session duration 選一小時。
4. 建立 Access policy：名稱 `管理者`、Action `Allow`、Include selector `Emails`，值填使用者指定的管理郵箱。不要選 Emails ending in／Everyone，也不要建立 Bypass 政策。只保護上述管理 hostname，不要保護供模組下載的公共 Worker。
5. 登入方式選 `One-time PIN`。若未列出，進入 `Zero Trust → Integrations → Identity providers → Add new identity provider` 加入 One-time PIN，再回應用選用。舊版介面對應 `Settings → Authentication → Login methods`。儲存並建立應用。
6. 在該應用的設定／Basic information 找到 `Application Audience (AUD) Tag`；在 `Zero Trust → Settings → Team name and domain` 找到 Team domain（部分介面位於 Custom pages）。
7. 提供完整 Team domain（`https://你的名稱.cloudflareaccess.com`）及該應用的 AUD。這兩項不是管理密鑰。助手會配置已部署的管理 Worker，然後再讓管理者完成一次實際登入。

官方操作文件：[建立應用](https://developers.cloudflare.com/learning-paths/clientless-access/access-application/create-access-app/)、[Worker hostname 保護](https://developers.cloudflare.com/workers/configuration/cloudflare-access/)、[Zero Trust 啟用](https://developers.cloudflare.com/cloudflare-one/setup/)、[登入方式](https://developers.cloudflare.com/cloudflare-one/integrations/identity-providers/)。

正式驗收仍需要管理者在瀏覽器完成登入。密碼、郵件驗證碼及登入 Cookie 不需要提供給助手。

Cloudflare 權限文件：[建立 Access 應用](https://developers.cloudflare.com/api/resources/zero_trust/subresources/access/subresources/applications/methods/create/)、[讀取 Zero Trust 組織](https://developers.cloudflare.com/api/resources/zero_trust/subresources/organizations/methods/list/)。
