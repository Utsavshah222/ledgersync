# LedgerSync: bank SMS to Google Sheet

LedgerSync is an Android app. It captures **every bank debit and credit SMS** on the phones it's installed on and writes each one to one Google Sheet — no setup, no account lists. The sheet groups everything automatically by bank account, keeps live balances and month/year totals, and has a **Filter** tab where you pick an account, bank, type and date range and export the result to **Excel or PDF**.

- **Captures every bank account automatically.** Nothing to configure — any bank's debit/credit SMS is picked up and grouped by `Bank ••last-4`.
- **Only real transactions.** Personal chats, OTPs, promotions, failed transactions and reminders are ignored.
- **Runs in the background.** It captures even when the app is closed, and survives reboots.
- **No duplicates.** Install it on as many phones as you like — each transaction appears in the sheet once.
- **Offline-safe.** Entries are queued on the phone and upload when there's internet.
- **Self-healing.** Every 15 minutes it re-checks the inbox, so it catches any SMS a phone's battery saver blocked.

## Two editions

The build produces **two apps that can live side by side on one phone**:

| Edition | Package | What it shows |
|---|---|---|
| **LedgerSync Admin** | `com.ledgersync.app` | The full app: all balances, totals, history and account cards. For the owner / accounts manager. |
| **LedgerSync Staff** | `com.ledgersync.app.staff` | Setup plus a small “it's working” status screen only. It captures and sends bank SMS but **shows no money data**. For staff phones. |

Both capture SMS the same way and feed the same sheet. Put **Staff** on staff phones and **Admin** on the manager's phone.

---

## 1. Set up the Google Sheet (one time, about 5 minutes)

1. Create a new Google Sheet, for example *"Company Bank Ledger"*.
2. Open **Extensions ▸ Apps Script**. Delete the sample code, then paste in all of [apps-script/Code.gs](apps-script/Code.gs).
3. In the Apps Script editor, open **Project Settings ⚙** and tick *"Show appsscript.json"*. Then replace that file's contents with [apps-script/appsscript.json](apps-script/appsscript.json).
4. Select the `setup` function and click **Run**. Allow the permissions it asks for (it needs Drive access so the Filter tab can export Excel/PDF files). This creates all the tabs, the secret key, and the export buttons.
5. Click **Deploy ▸ New deployment ▸ Web app**:
   - Execute as: **Me**
   - Who has access: **Anyone**
   - Click **Deploy** and copy the **Web app URL** (it ends in `/exec`).
6. Back in the sheet, reload the page. Then open **LedgerSync ▸ Connection details** and copy the **secret key**.

That's it — there's no account list to fill in. Accounts appear on their own as soon as the first SMS for them is captured.

> If you change `Code.gs` later, use **Deploy ▸ Manage deployments ▸ Edit ▸ New version**. That keeps the same URL. If the editor shows new permissions after pasting, run `setup` again and allow them.
>
> If you edit or delete transaction rows by hand, run **LedgerSync ▸ Recalculate app totals** so the app's totals match.

## 2. Install the apps on each phone

1. Download the right APK onto the phone — **LedgerSync-Staff** for staff phones, **LedgerSync-Admin** for the manager's phone — and open it. Allow *"Install unknown apps"* if Android asks.
2. Follow the setup screens:
   - Allow **SMS** (required), **Notifications**, and **Run in background**.
   - Paste the **Web App URL** and the **secret key**, and give the phone a name, for example "Accounts Manager".
   - Optionally import this month's or this year's older SMS.
3. **Xiaomi, Redmi, Vivo, Oppo, Realme, OnePlus:** go to **Settings ▸ Always-on capture ▸ Autostart & app settings**. Turn on **Autostart** and set battery to **No restrictions**.

## 3. What the sheet contains

| Tab | Contents |
|---|---|
| **Dashboard** | One row per detected account: current balance; This Month, This Year and All-time credit, debit and net; last transaction. Plus a Grand total. All live formulas. |
| **Monthly** | Month-wise totals overall, month-wise per account, and year-wise per account |
| **Master** | Every transaction, newest first: date, account, bank, type, credit, debit, balance after, mode (UPI/NEFT/IMPS/ATM/Card…), party, reference, device, original SMS |
| **Filter** | Pick an **account, bank, type and date range** from dropdowns → the matching rows and live Credit / Debit / Net / count appear instantly. Tick **Export to Excel** or **Export to PDF** (or use the **LedgerSync** menu) to download exactly what's filtered. Files are also saved to Drive ▸ *LedgerSync Exports*. |
| **Accounts** | The accounts detected so far, with the latest balance — filled in automatically |
| **Devices** | Which phones are sending data, and when each last synced |

## 4. App features

**Admin edition**
- **Home:** total balance of every detected account; money in, money out and net for This Month, This Year or All Time; per-account cards; the newest entries from all phones; live sync status.
- **Activity:** the full transaction history from **all devices**, 25 at a time (more loads as you scroll), grouped by day, with search and account / money-in / money-out filters. Switch to **This phone** to see only what this phone captured, including entries still waiting for internet. Tap an entry to see full details, which phone captured it, and the original SMS.
- **Accounts:** a read-only list of every account discovered from the SMS, with its latest balance.
- **Settings:** connection, sync now, history import, battery and autostart help, and the **Parser Lab** — paste any bank SMS to see exactly how it will be read.

**Staff edition**
- A single status screen: *Connected*, *Capturing bank SMS*, last sync and a **Sync now** button, plus the same connection / import / battery settings. It never shows balances, history or amounts.

**Both**
- **Works offline:** with no internet, new SMS are queued on the phone and upload automatically when it's back online.

## Publishing the APKs with GitHub (no Play Store)

GitHub builds **both** APKs automatically and posts them on the repo's **Releases** page. You download the one each phone needs from there.

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
After about 5 minutes the two APKs appear under **Releases** (`LedgerSync-Admin-v…apk` and `LedgerSync-Staff-v…apk`). To ship an update, push a higher tag (`v1.0.1`, `v1.1.0`, …). Phones install it over the old version and keep all data.

> **Back up `C:\Users\utsav\ledgersync-keys\`** (for example to a pen drive or a password manager). Every update must be signed with this same key. If you lose it, every phone has to uninstall and reinstall the app.

**Public or private repo?**
- **Private:** only you can see the code and the APKs. To download on a phone, sign in to github.com in the phone's browser. The in-app "Update available" banner won't appear, so check Releases yourself.
- **Public:** anyone with the link can download the APKs, and the app shows an "Update available" banner automatically (each edition finds its own APK in the release). No passwords or keys are in the code (the sheet's secret key lives only in Apps Script and on the phones).

## Building from source

Requires JDK 17 and the Android SDK (platform 35).

```
./gradlew testAdminDebugUnitTest   # parser tests against real bank SMS formats
./gradlew assembleRelease          # → app/build/outputs/apk/{admin,staff}/release/app-*-release.apk
```

Project layout:

```
app/src/main/java/com/ledgersync/app/
  sms/SmsParser.kt      bank SMS parsing, auto account identity, de-duplication key
  sms/SmsReceiver.kt    real-time capture (works while the app is closed)
  sms/Ingest.kt         store + inbox scanner
  sync/SyncWorker.kt    background upload, retry, 15-min safety-net scan
  sync/SheetApi.kt      Google Apps Script client
  ui/…                  Jetpack Compose screens (Admin shell + Staff status screen)
apps-script/Code.gs     Google Sheet backend (dashboard, master, filter + export, auto-accounts, de-dup, totals)
```

**Build editions:** `admin` and `staff` Gradle product flavors (different `applicationId`, so both install on one phone). The edition is read at runtime from `BuildConfig.IS_ADMIN`.

**Note:** the app reads SMS, so it can't be published on the Google Play Store. Google restricts SMS permissions to default messaging apps. It is meant for direct (side-loaded) installation on company phones.
