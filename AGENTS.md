# 給 AI 助手的說明（AGENTS.md）

你在協助使用者修改一個**自己建置的個人 Android APP**。使用者多半不是工程師，請用白話、一步一步說明，改動前先講清楚要改什麼。

## 專案重點
- Kotlin、Jetpack Compose、Gradle Kotlin DSL。最低 SDK 29，target SDK 35，JDK 17。
- 模組：
  - `android/core`：純 Kotlin，沒有 Android 依賴。包含資料模型（`core/model`）、中文時間判讀（`TimeParser`）、整理邏輯（規則版 `TaskParser`、AI 版 `GeminiTaskParser`、模型切換 `ModelRouting`）和每週統計。
  - `android/feature/telegram`：純 Kotlin 的 Telegram 收件匣（`InboxBot`、`TelegramApi`、`Replies`），透過 `Ports.kt` 的介面跟 APP 溝通。
  - `android/app`：介面、Firestore 存取、鬧鐘提醒、分享進 APP、語音輸入、Telegram 背景服務（`TelegramService`、`TelegramWorker`、`InboxConnectionService`）。
- 沒有伺服器。APP 自己呼叫 Gemini REST API，自己用 `getUpdates` 長輪詢 Telegram，資料存在使用者自己的 Firestore（`users/{uid}/...`）。
- 資料格式在 `docs/schema.md`。改欄位時，APP 和 Telegram 模組都要一起改，並更新這份文件。舊資料要能繼續讀（新欄位給預設值），不要直接改名或刪除欄位。
- 介面文字一律用**繁體中文**、口語，不用技術術語。

## 建置與測試
- `cd android && ./gradlew :core:test :feature:telegram:test` 可以跑純 Kotlin 的單元測試。改 `core` 或 `feature/telegram` 時要補測試，並確認測試通過。
- 完整的 APK 建置靠 GitHub Actions（`.github/workflows/build.yml`），推到 GitHub 後就會自動建置並發到 Releases。如果你的環境沒有 Android SDK，就以 CI 的結果為準，不要宣稱已經建置成功。
- `.github/workflows/setup.yml` 只會產生一次簽章金鑰。**絕對不要**重產或替換 `android/app/release.jks`，否則 Firebase 的 SHA-1 會對不上，Google 登入就會失敗。

## 機密與安全（很重要）
- `android/app/google-services.json`、`release.jks`、`keystore.properties` 只能放在使用者的 **Private** repo。如果 repo 是公開的，請先提醒使用者改成 Private。
- Gemini API key 和 Telegram bot token 是在 APP 設定頁輸入，加密存在手機裡（EncryptedSharedPreferences）。**不要**把這些值寫進程式碼、設定檔、commit、log 或 issue。
- 不要放寬 `firestore.rules`。目前的規則是每個人只能讀寫自己的 `users/{uid}`。
- Telegram bot 只回應已連結的 chatId。不要移除這項檢查。

## 免費額度的限制
- 預設模型是 Gemini Flash-Lite（免費每天約 500 次）。截圖、長文或沒把握的結果才會升級到 Flash（免費每天約 20 次），APP 有每天的自動升級上限，見 `ModelRouting`。新增 AI 呼叫時請考慮額度，並保留「失敗時改用規則版」的退路。
- Firebase 用 Spark 免費方案，不能用 Cloud Functions。需要伺服器的功能（例如即時的 Telegram webhook）要先跟使用者說明會產生費用。
- Firestore 的免費額度是每天讀取 5 萬次、寫入 2 萬次。避免輪詢或大量重複寫入。

## 跟原始版本同步
這個 repo 是從公開範本 `shishonomisaki/life-manager-public` 建立的。同步新版時，不要覆蓋上面三個機密檔案。有衝突時，以使用者自己的修改為優先，並先詢問使用者。
