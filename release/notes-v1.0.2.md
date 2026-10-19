# 游戲版本支援 1.21.11, 26.1.2, 26.2, 26.3

## 魔塔輔助模組 新增/調整
- 狀態二次確認，每一次重連都恢復為自動偵測
<div align="center">
<img width="1543" height="335" alt="image" src="https://github.com/user-attachments/assets/d4c996ea-54dd-4411-adbb-01f851a81ee4" />
<br>
<br>
</div>

- 新增HUD，如下：
  - 你在哪一層
  - 這層是否有撤離室
  - 物品欄中有多少貓幣，計算還可以再塞多少
<div align="center">
<img width="1919" height="993" alt="image" src="https://github.com/user-attachments/assets/9e49b1dd-c5ab-44e6-9e28-7d6d5d57401b" />
<br>
<br>
</div>

- 修復離目標太遠導致路線失聯
- 修復標記快捷鍵在未進入魔塔時也能觸發

<h2>
<img width="37"  alt="" align="absmiddle" src="https://github.com/user-attachments/assets/175a2c2f-2eed-41e7-9d2d-bff5794eda07" />
  &nbsp;全新 公共傳點工具模組
</h2>

先介紹設計目的：
```
這個專案最初的目的是解決玩家離線時無法查詢其傳點的問題。

由此設計這個模組，透過安裝該模組的玩家，在不同時間點定期檢測 /to by <補全> 指令所返回的傳點清單，並自動收集清單將其上傳至雲端進行儲存。

透過這種方式逐步建立一個共享的傳送點資料庫。即使目標玩家不在線上，其他玩家仍然可以透過雲端保存的紀錄查詢其傳送點，而不必依賴遊戲內即時提供的資料。
```

<div align="center">
<img width="1536" height="789" alt="image" src="https://github.com/user-attachments/assets/1e0c9b55-7c28-4c78-b1eb-183b69183386" />
<br>
<br>
</div>

使用方式：默認快捷鍵是 `X`，點擊即可開啟介面。


## 内容包含
- catbud-core
- catbud-magic_tower
- catbud-public_warps
## 依賴
上述内容附加都需要依賴 catbud-core 才能夠運行。
