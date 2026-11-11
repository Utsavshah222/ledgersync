# LedgerSync: bank SMS to Google Sheet

LedgerSync is an Android app. It captures every **debit and credit SMS** for the company's chosen bank accounts and writes it to one Google Sheet. The sheet has a master tab, one tab per account, and live month, year and all-time totals.

- **Only tracked accounts are captured.** Personal SMS, OTPs, promotions, failed transactions and SMS for untracked accounts are ignored.
- **It runs in the background.** It captures messages even when the app is closed, and survives reboots.
- **No duplicates.** Install it on as many phones as you like. Each transaction appears in the sheet once.
- **Offline-safe.** Entries are queued on the phone and upload when there's internet.
- **Self-healing.** Every 15 minutes it re-checks the inbox, so it catches any SMS that a phone's battery saver blocked.

---

## 1. Set up the Google Sheet (one time, about 5 minutes)

1. Create a new Google Sheet, for example *"Company Bank Ledger"*.
2. Open **Extensions ▸ Apps Script**. Delete the sample code, then paste in all of [apps-script/Code.gs](apps-script/Code.gs).
3. In the Apps Script editor, open **Project Settings ⚙** and tick *"Show appsscript.json"*. Then replace that file's contents with [apps-script/appsscript.json](apps-script/appsscript.json).
4. Select the `setup` function and click **Run**. Allow the permissions it asks for. This creates the tabs and a secret key.
5. Click **Deploy ▸ New deployment ▸ Web app**:
   - Execute as: **Me**
   - Who has access: **Anyone**
   - Click **Deploy** and copy the **Web app URL** (it ends in `/exec`).
6. Back in the sheet, reload the page. Then open **LedgerSync ▸ Connection details** and copy the **secret key**.
7. In the **Accounts** tab, add one row per bank account:

| Nickname | Bank | Account Number | Owner | Linked Card Last-4 | Paused |
|---|---|---|---|---|---|
| HDFC Current | HDFC Bank | 50200012341234 | Company | 9876 | ☐ |
| SBI Main | SBI | 38123456785678 | Company | | ☐ |
| Owner Kotak | Kotak Bank | 1234567890 | Personal | | ☐ |

You can also add accounts from the app later. The full number is best, but the last 4 digits are enough.

> If you change `Code.gs` later, use **Deploy ▸ Manage deployments ▸ Edit ▸ New version**. That keeps the same URL.
>
> If you edit or delete transaction rows by hand, run **LedgerSync ▸ Recalculate app totals** so the app's totals match.

## 2. Install the app on each phone

1. Copy `LedgerSync-v1.0.0.apk` to the phone and open it. Allow *"Install unknown apps"* if Android asks.
2. Follow the setup screens:
   - Allow **SMS** (required), **Notifications**, and **Run in background**.
   - Paste the **Web App URL** and the **secret key**, and give the phone a name, for example "Accounts Manager".
   - Optionally import this month's or this year's older SMS.
3. **Xiaomi, Redmi, Vivo, Oppo, Realme, OnePlus:** go to **Settings ▸ Always-on capture ▸ Autostart & app settings**. Turn on **Autostart** and set battery to **No restrictions**.

## 3. What the sheet contains

| Tab | Contents |
|---|---|
| **Dashboard** | One row per account: current balance; This Month, This Year and All-time credit, debit and net; last transaction. Plus Company, Personal and Grand totals. All live formulas. |
| **Monthly** | Month-wise totals overall, month-wise per account, and year-wise per account |
| **Master** | Every transaction, newest first: date, account, bank, owner, type, credit, debit, balance after, mode (UPI/NEFT/IMPS/ATM/Card…), party, reference, device, original SMS |
| **One tab per account** | The same columns, for that account only |
| **Accounts** | The list of tracked accounts, with the latest balance filled in automatically |
| **Devices** | Which phones are sending data, and when each last synced |

## 4. App features

- **Home:** total balance of every account with a Company / Personal / All filter; money in, money out and net for This Month, This Year or All Time; per-account cards; the newest entries from all phones; live sync status.
- **Activity:** the full transaction history from **all devices**, 25 at a time (more loads as you scroll), grouped by day, with search and account / money-in / money-out filters. Switch to **This phone** to see only what this phone captured, including entries still waiting for internet. Tap an entry to see full details, which phone captured it, and the original SMS.
- **Works offline:** with no internet, new SMS are queued on the phone and the balances and totals update straight away. Everything uploads automatically when the phone is back online. Screens open instantly from a saved copy and refresh in the background.
- **Accounts:** the tracked accounts, plus adding new ones (they sync to every phone).
- **Settings:** connection, sync now, history import, battery and autostart help, and the **Parser Lab**. Paste any bank SMS into the Parser Lab to see exactly how it will be read.

## Publishing the APK with GitHub (no Play Store)

GitHub builds the APK automatically and posts it on the repo's **Releases** page. You download it from there on each phone.

**One-time setup:**
1. Create a new repository on github.com and push this project to it.
2. In the repo, go to **Settings ▸ Secrets and variables ▸ Actions ▸ New repository secret** and add these four secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the full contents of `C:\Users\utsav\ledgersync-keys\keystore-base64.txt` |
| `KEYSTORE_PASSWORD` | `storePassword` from `C:\Users\utsav\ledgersync-keys\keystore.properties` |
| `KEY_ALIAS` | `ledgersync` |
| `KEY_PASSWORD` | `keyPassword` from the same file |

**To publish a version:**
```
git tag v1.0.0
git push origin v1.0.0
```
After about 5 minutes the APK appears under **Releases**. To ship an update, push a higher tag (`v1.0.1`, `v1.1.0`, …). Phones install it over the old version and keep all data.

> **Back up `C:\Users\utsav\ledgersync-keys\`** (for example to a pen drive or a password manager). Every update must be signed with this same key. If you lose it, every phone has to uninstall and reinstall the app.

**Public or private repo?**
- **Private:** only you can see the code and the APK. To download on a phone, sign in to github.com in the phone's browser. The in-app "Update available" banner won't appear, so check Releases yourself.
- **Public:** anyone with the link can download the APK, and the app shows an "Update available" banner automatically. No passwords or keys are in the code (the sheet's secret key lives only in Apps Script and on the phones). But the project is visible to anyone who finds it.

## Building from source

Requires JDK 17 and the Android SDK (platform 35).

```
./gradlew testDebugUnitTest     # 25 parser tests against real bank SMS formats
./gradlew assembleRelease       # → app/build/outputs/apk/release/app-release.apk
```

Project layout:

```
app/src/main/java/com/ledgersync/app/
  sms/SmsParser.kt      bank SMS parsing, account matching, de-duplication key
  sms/SmsReceiver.kt    real-time capture (works while the app is closed)
  sms/Ingest.kt         store + inbox scanner
  sync/SyncWorker.kt    background upload, retry, 15-min safety-net scan
  sync/SheetApi.kt      Google Apps Script client
  ui/…                  Jetpack Compose screens
apps-script/Code.gs     Google Sheet backend (dashboard, tabs, de-dup, totals)
```

**Note:** the app reads SMS, so it can't be published on the Google Play Store. Google restricts SMS permissions to default messaging apps. It is meant for direct (side-loaded) installation on company phones.
