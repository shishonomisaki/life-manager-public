# 共用資料模型

> APP 和 Telegram Bot 共用這份資料模型。改欄位時兩邊都要跟著改，並更新這份文件。

## 資料模型（Firestore）

所有資料放在 `users/{uid}/` 底下，安全規則只允許本人讀寫；Cloud Functions 用服務帳號寫入。

### `users/{uid}`（使用者設定）
```
displayName, email
timezone: "Asia/Taipei"
weekStartsOn: 1                 // 1 = 週一
telegram: { chatId, linkedAt }  // Bot 綁定後才有
topics: ["工作","家庭","個人","知識"]   // 預設主題，可自訂
```

### `users/{uid}/projects/{projectId}`（專案，借用專案管理的做法）
```
name: string                    // 例如「搬家」「Q4 報價」「年度保險整理」
topic: string                   // 所屬主題
goal: string?                   // 一句話說明要達成什麼
status: "active"|"paused"|"done"|"archived"
dueAt: Timestamp?               // 專案期限
createdAt, updatedAt
```
專案進度（完成幾項、還剩幾項、下一步是什麼）由 tasks 即時算，不另存。沒有專案的待辦就是一般的單次事項。

### `users/{uid}/tags/{tagId}`（標籤）
```
name: string                    // 例如「打電話」「付款」「出門辦」「等回覆」「5 分鐘」
color: string
useCount: number                // 常用的排前面
```
預設附一組常用標籤：打電話、付款、出門辦、線上辦、等回覆、5 分鐘可做、要討論。

### `users/{uid}/tasks/{taskId}`（待辦，APP 和 Bot 共用的核心）
```
title: string                   // 必填，一句話
notes: string?                  // 補充說明、原文摘要
topic: string                   // 對應 topics 之一，預設 "個人"；之後同步 Claude 專案就靠這欄
projectId: string?              // 所屬專案
parentId: string?               // 子任務指向上層待辦（只支援一層）
tags: string[]                  // tagId 清單
autoTagged: string[]            // 哪些欄位是系統自動選的（"topic"|"projectId"|"tags"|"priority"），你改過的會移除
priority: 0|1|2                 // 一般 / 重要 / 緊急
status: "todo"|"doing"|"waiting"|"done"|"cancelled"
waitingFor: string?             // 狀態為 waiting 時，在等誰或等什麼
dueAt: Timestamp?               // 截止時間
remindAt: Timestamp?            // 提醒時間（可和 dueAt 不同）
recurrence: string?             // RRULE，例如 "FREQ=WEEKLY;BYDAY=MO"；v1 先支援每天/每週/每月
completedAt: Timestamp?
createdAt, updatedAt: Timestamp // 伺服器時間
source: {
  kind: "manual"|"voice"|"telegram",
  inboxId: string?,             // 從 Bot 來的話指向 inbox
  confidence: number?           // AI 判讀信心 0–1，低於 0.6 在 APP 標記「待確認」
}
version: number                 // 每次修改 +1，避免兩邊同時改互相覆蓋
```

### `users/{uid}/inbox/{inboxId}`（Bot 收到的原始內容）
```
kind: "text"|"voice"|"photo"|"forward"
text: string?                   // 文字或語音轉出的文字
mediaPath: string?              // Cloud Storage 路徑（截圖、語音），保留 30 天
forwardFrom: string?            // 轉傳來源名稱
tgMessageId, tgChatId
status: "pending"|"parsed"|"failed"|"ignored"
taskIds: string[]               // 這則訊息產生了哪些待辦
createdAt
```

### 每週統計
v1 在 APP 端即時算，不另存：以 `weekStartsOn` 和台北時區切週，計算
- 新增數、完成數、完成率
- 準時完成率（completedAt ≤ dueAt）
- 逾期未完成數
- 依 topic 分類的完成數，以及來源（手動 / 語音 / Bot）占比

之後若網頁版或 Claude 專案要讀，再加一個每週一凌晨跑的函式，把結果寫進 `users/{uid}/weeklyStats/{2026-W41}`。

## 實作補充（2026-10-06，第 2 串）

- `tasks.suggestedProject: string?`：AI 覺得該開新專案時的建議名稱；使用者在 APP 按一下才建立專案並清掉這欄。
- 預設標籤的 tagId 固定，APP 和 Bot 可以直接用：`call` 打電話、`pay` 付款、`errand` 出門辦、`online` 線上辦、`waiting` 等回覆、`quick` 5 分鐘可做、`discuss` 要討論。
- `autoTagged` 的值：`topic`、`projectId`、`tags`、`priority`。APP 編輯時改過的欄位會從清單移除。
- `version`：每次寫入用 `FieldValue.increment(1)`。APP 為了離線也能改，不用交易；儲存時如果發現別的裝置先改過，會提示「已被其他裝置更新」，以最後寫入為準。
- 只有日期沒有時刻（例如「週五前」）時，`dueAt`／`remindAt` 設為當天 09:00（台北）。
- 重複規則 `recurrence` 支援 `FREQ=DAILY`、`FREQ=WEEKLY[;BYDAY=MO,TU…]`、`FREQ=MONTHLY[;BYMONTHDAY=n]`。完成時會建立下一次的新待辦，原本那筆留著當完成紀錄。
- 整理邏輯（`TaskParser`）放在 `android/core`（純 Kotlin，可在 JVM 單元測試）：`RuleTaskParser` 規則版、`GeminiTaskParser` Gemini 版、`FallbackTaskParser` 組合。Telegram 收件匣直接用 `parse()`（Gemini 版另有可帶截圖／語音的多載）。

## 實作補充（2026-10-06，第 3 串 Telegram 收件匣，階段 0）

- `inbox` 照上面的欄位寫入；階段 0 不存媒體檔，所以沒有 `mediaPath`（截圖、語音直接從 Telegram 下載交給 Gemini）。語音的聽寫原文放在產生的待辦 `notes`。
- `inbox.taskIds` 只記上層待辦，子任務用 `parentId` 找。整理不出待辦時 `status: "ignored"`、`taskIds: []`，使用者按「還是建立」後改成 `parsed`。
- Bot 建立的待辦：`source.kind = "telegram"`、`source.inboxId`、`source.confidence`；轉傳的訊息在 `notes` 開頭寫「轉傳自：名字」。
- 綁定：階段 0 沒有 Cloud Functions，一次性代碼只存在手機上，不用 `linkCodes` 集合。綁定成功寫 `users/{uid}.telegram = { chatId, linkedAt }`。Bot 金鑰和收訊進度也只存在手機（加密）。
- `source.kind` 新增 `"share"`：從其他 APP 的「分享」選單進來（LINE、相簿等）。整理流程和 Bot 相同，也會寫一筆 `inbox`（`tgMessageId`、`tgChatId` 為 0）。統計頁的「收件匣」= `telegram` + `share`。
- `inbox` 新增（第 3 串，2026-10-08）：`replyMessageId`（Bot 回覆整理結果的訊息）、`fileRefs`（Telegram 附件「mime|fileId」，Gemini 恢復後重新下載）、`retryAi`（Gemini 忙線時先用規則整理或語音先等著，恢復後再整理一次）、`retries`。語音在等 Gemini 時 `status: "pending"`、`taskIds: []`；放棄重試時改成 `failed`。
- `inbox.model`（第 3 串，2026-10-20）：用了哪個模型。`lite`；`strong:A1`（升級和原因，原因代碼見 plan/model-routing.md，`manual` 是使用者回覆「重整」）；`lite:B2`（該升級但今天強模型用完）；`rules`（沒用 AI）。之後拿來調升級門檻。
- `ParseContext.guidance`、`ParseResult.heard`／`command`（core，向下相容）：使用者的分類線索給 Gemini 參考；Gemini 聽寫的語音原文；內容是指令時（list、today、week、done 1 2、done all）回傳指令。分類線索（每個主題的關鍵字、在 Bot 改過的分類）只存在手機，不進 Firestore。

