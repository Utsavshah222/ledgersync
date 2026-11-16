/**
 * LedgerSync — Google Sheets backend
 * ------------------------------------------------------------
 * Paste this whole file into  Extensions ▸ Apps Script  of the company spreadsheet,
 * run  setup  once, then  Deploy ▸ New deployment ▸ Web app  (Execute as: Me, Access: Anyone).
 *
 * The phones now capture EVERY bank debit/credit SMS automatically — there is no account list to maintain.
 * The sheet groups transactions on its own, by  "<Bank> ••<last 4>",  so no per-account tabs are needed.
 *
 * Tabs it maintains:
 *   Dashboard  – live balance + This Month / This Year / All-time credit, debit, net for every detected account
 *   Monthly    – month-by-month totals (overall and per account)
 *   Master     – every transaction from every phone, newest first
 *   Filter     – pick account / bank / type / date range → matching rows + totals, and export to Excel or PDF
 *   Accounts   – the accounts detected so far, with their latest balance (filled in automatically)
 *   Devices    – which phones are sending data and when they last synced
 */

const TAB = { DASH: 'Dashboard', MONTHLY: 'Monthly', MASTER: 'Master', FILTER: 'Filter', ACCOUNTS: 'Accounts', DEVICES: 'Devices' };

const TXN_HEADERS = ['Txn ID', 'Date & Time', 'Month', 'Year', 'Account', 'Bank', 'A/c No.', 'Owner', 'Type',
  'Credit (₹)', 'Debit (₹)', 'Balance After (₹)', 'Mode', 'Party / Merchant', 'Reference', 'Sender', 'Device', 'Original SMS', 'Synced At'];
const C = { ID: 1, DT: 2, MONTH: 3, YEAR: 4, ACC: 5, BANK: 6, NO: 7, OWNER: 8, TYPE: 9, CR: 10, DR: 11, BAL: 12, MODE: 13, PARTY: 14, REF: 15, SENDER: 16, DEVICE: 17, SMS: 18, SYNCED: 19 };

// Accounts tab is now auto-discovered (read-only for the user); one row per account LedgerSync has seen.
const ACC_HEADERS = ['Account', 'Bank', 'A/c No.', 'Last Balance (₹)', 'Balance As Of'];
const A = { LABEL: 1, BANK: 2, NO: 3, BAL: 4, BAL_AT: 5 };

const FMT = '"₹"#,##0.00';
const FMT_NET = '"₹"#,##0.00;[Red]-"₹"#,##0.00';

let tz_ = null;
const TZ = () => tz_ || (tz_ = Session.getScriptTimeZone() || 'Asia/Kolkata');

const API_VERSION = 3;
const PAGE = 25;            // default page size for the app's history lists
const CACHE_TTL = 600;      // seconds; every write bumps REV so cached replies never go stale
const COLORS = { header: '#1E1B4B', headerText: '#FFFFFF', credit: '#0F9D58', debit: '#D93025', band: '#F5F3FF', accent: '#6D4BFF' };

// Filter tab layout (used by the formulas and the export buttons)
const F = {
  ACC: 'B4', BANK: 'B5', TYPE: 'B6', FROM: 'B7', TO: 'B8',
  XLS_ROW: 4, XLS_COL: 7, PDF_ROW: 6, PDF_COL: 7, // checkbox "buttons" at G4 / G6
  RESULT_ROW: 11, RESULT_COLS: 12,
  LIST_ACC: 16, LIST_BANK: 17, // hidden helper lists in columns P / Q
};

// ============================================================ MENU / SETUP

function onOpen() {
  SpreadsheetApp.getUi().createMenu('LedgerSync')
    .addItem('Connection details (for the app)', 'showConnection')
    .addItem('Rebuild Dashboard, Monthly & Filter', 'rebuildReports')
    .addItem('Re-sort all sheets (newest first)', 'resortAll')
    .addItem('Recalculate app totals (after hand edits)', 'recalcTotals')
    .addSeparator()
    .addItem('⬇ Export current filter to Excel', 'exportFilterToExcel')
    .addItem('⬇ Export current filter to PDF', 'exportFilterToPdf')
    .addSeparator()
    .addItem('Run first-time setup', 'setup')
    .addItem('Generate NEW secret key (disconnects phones)', 'rotateSecret')
    .addToUi();
}

function setup() {
  const ss = SpreadsheetApp.getActive();
  ss.setSpreadsheetTimeZone(ss.getSpreadsheetTimeZone() || 'Asia/Kolkata');
  const props = PropertiesService.getScriptProperties();
  if (!props.getProperty('SECRET')) props.setProperty('SECRET', makeSecret_());

  ensureTxnSheet_(ss, TAB.MASTER);
  ensureAccountsSheet_(ss);
  ensureDevicesSheet_(ss);
  rebuildReports();
  ensureEditTrigger_();
  orderTabs_(ss);
  const first = ss.getSheetByName('Sheet1');
  if (first && first.getLastRow() === 0 && ss.getSheets().length > 1) ss.deleteSheet(first);

  Logger.log('Setup complete. Secret key: ' + props.getProperty('SECRET'));
  try { showConnection(); } catch (e) { /* run from editor: no UI */ }
}

/** The export buttons on the Filter tab need an installable onEdit trigger (it runs with full authorization). */
function ensureEditTrigger_() {
  try {
    const have = ScriptApp.getProjectTriggers().some(t => t.getHandlerFunction() === 'onEditInstallable');
    if (!have) ScriptApp.newTrigger('onEditInstallable').forSpreadsheet(SpreadsheetApp.getActive()).onEdit().create();
  } catch (e) { /* non-fatal: the LedgerSync menu still exports */ }
}

function showConnection() {
  const secret = PropertiesService.getScriptProperties().getProperty('SECRET') || '(run setup first)';
  let url = '';
  try { url = ScriptApp.getService().getUrl() || ''; } catch (e) {}
  const html = HtmlService.createHtmlOutput(
    '<div style="font-family:Arial;font-size:14px;line-height:1.5">' +
    '<p><b>1. Web App URL</b><br>' + (url ? '<textarea style="width:100%;height:60px">' + url + '</textarea>'
      : '<span style="color:#D93025">Not deployed yet — Deploy ▸ New deployment ▸ Web app (Execute as: Me, Who has access: Anyone). Then copy the URL ending in /exec.</span>') + '</p>' +
    '<p><b>2. Secret key</b><br><textarea style="width:100%;height:40px">' + secret + '</textarea></p>' +
    '<p style="color:#666">Paste both into LedgerSync on each phone (Settings ▸ Google Sheet connection).</p></div>'
  ).setWidth(460).setHeight(330);
  SpreadsheetApp.getUi().showModalDialog(html, 'LedgerSync connection');
}

function rotateSecret() {
  PropertiesService.getScriptProperties().setProperty('SECRET', makeSecret_());
  showConnection();
}

function makeSecret_() {
  return Utilities.getUuid().replace(/-/g, '').slice(0, 24).toUpperCase();
}

// ============================================================ WEB API

function doGet(e) {
  return handle_(() => {
    const p = (e && e.parameter) || {};
    auth_(p.key);
    const ss = SpreadsheetApp.getActive();
    switch (p.action) {
      case 'ping': return { sheet: ss.getName(), accounts: readAccounts_(ss).length, api: API_VERSION, time: new Date().toISOString() };
      case 'config': syncReportsIfAccountsChanged_(ss); return { accounts: readAccounts_(ss).map(publicAccount_) };
      case 'summary': return summary_(ss);
      case 'home': return home_(ss);
      case 'history': return history_(ss, p);
      default: throw new Error('Unknown action');
    }
  });
}

function doPost(e) {
  return handle_(() => {
    const body = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    auth_(body.key);
    const ss = SpreadsheetApp.getActive();
    switch (body.action) {
      case 'push': return push_(ss, body);
      case 'addAccount': return addAccount_(ss, body.account || {});
      default: throw new Error('Unknown action');
    }
  });
}

function handle_(fn) {
  let out;
  try { out = Object.assign({ ok: true }, fn()); }
  catch (err) { out = { ok: false, error: String(err && err.message || err) }; }
  return ContentService.createTextOutput(JSON.stringify(out)).setMimeType(ContentService.MimeType.JSON);
}

function auth_(key) {
  const secret = PropertiesService.getScriptProperties().getProperty('SECRET');
  if (!secret) throw new Error('Run setup in the spreadsheet first');
  if (!key || String(key).trim() !== secret) throw new Error('Wrong secret key');
}

// ============================================================ PUSH TRANSACTIONS

function push_(ss, body) {
  const txns = Array.isArray(body.txns) ? body.txns : [];
  const device = String(body.device || 'Unknown device').slice(0, 60);
  const results = {};
  if (!txns.length) return { results: results };

  let newAccounts = false;
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const master = ensureTxnSheet_(ss, TAB.MASTER);
    const before = Math.max(master.getLastRow() - 1, 0);
    const existing = new Set(readIds_(master));

    const now = new Date();
    const rows = [];
    const moves = {};

    txns.forEach(t => {
      const id = String(t.id || '').trim();
      if (!id) return;
      if (existing.has(id)) { results[id] = 'duplicate'; return; }
      existing.add(id);

      const bank = String(t.bank || '');
      const masked = String(t.masked || '');
      const nick = String(t.account || '').trim() || (bank + ' ' + masked).trim() || 'Unknown';
      const dt = new Date(Number(t.ts) || Date.now());
      const credit = t.type === 'CREDIT';
      const amount = round2_(Number(t.amount) || 0);
      const bal = (t.balance === null || t.balance === undefined || t.balance === '') ? '' : round2_(Number(t.balance));
      const row = [
        id, dt,
        Utilities.formatDate(dt, TZ(), 'yyyy-MM'),
        Number(Utilities.formatDate(dt, TZ(), 'yyyy')),
        nick, bank, masked, String(t.owner || ''),
        credit ? 'CREDIT' : 'DEBIT',
        credit ? amount : '',
        credit ? '' : amount,
        bal,
        String(t.mode || ''), String(t.party || ''), String(t.ref || ''),
        String(t.sender || ''), device, String(t.sms || '').slice(0, 1000), now,
      ];
      rows.push(row);
      (moves[nick] = moves[nick] || []).push({ dt: dt, delta: credit ? amount : -amount, bal: bal });
      results[id] = 'added';
    });

    if (rows.length) {
      appendSorted_(master, rows);
      newAccounts = ensureAccountRows_(ss, rows);
      updateBalances_(ss, moves);
      addToStats_(rows, before);
      bumpRev_();
    }
    touchDevice_(ss, device, rows.length);
  } finally {
    lock.releaseLock();
  }

  // A brand-new account showed up — refresh the Dashboard and the Filter dropdowns to include it.
  if (newAccounts) { try { rebuildReports(); } catch (e) {} }
  return { results: results, added: Object.keys(results).filter(k => results[k] === 'added').length };
}

/**
 * Keeps every transaction tab newest-first without re-sorting the whole sheet each time.
 * Live SMS are always newer than the top row, so they slide in right under the header (fast path).
 * Back-dated rows (history import) are appended and the sheet is sorted once.
 */
function appendSorted_(sh, rows) {
  if (!rows.length) return;
  const W = TXN_HEADERS.length;
  rows.sort((a, b) => b[C.DT - 1] - a[C.DT - 1]);
  const last = sh.getLastRow();
  if (last < 2) {
    ensureRows_(sh, 1 + rows.length);
    sh.getRange(2, 1, rows.length, W).setValues(rows);
    return;
  }
  const top = sh.getRange(2, 1, 1, W).getValues()[0];
  const topDt = top[C.DT - 1] instanceof Date ? top[C.DT - 1].getTime() : NaN;
  if (!isNaN(topDt) && rows[rows.length - 1][C.DT - 1].getTime() >= topDt) {
    sh.insertRowsAfter(2, rows.length);
    sh.getRange(2, 1, rows.length + 1, W).setValues(rows.concat([top]));
    return;
  }
  ensureRows_(sh, last + rows.length);
  sh.getRange(last + 1, 1, rows.length, W).setValues(rows);
  sh.getRange(2, 1, last + rows.length - 1, W).sort({ column: C.DT, ascending: false });
}

/** A new sheet only has 1000 grid rows; writing past that fails. Grow it from inside so formatting carries over. */
function ensureRows_(sh, needLastRow) {
  const max = sh.getMaxRows();
  if (needLastRow <= max) return;
  const extra = needLastRow - max + 1000;
  if (max > 2) sh.insertRowsBefore(max, extra); else sh.insertRowsAfter(max, extra);
}

function readIds_(sh) {
  const last = sh.getLastRow();
  if (last < 2) return [];
  return sh.getRange(2, C.ID, last - 1, 1).getValues().map(r => String(r[0]));
}

/** Makes sure the Accounts tab has a row for every account label in [rows]. Returns true if any were added. */
function ensureAccountRows_(ss, rows) {
  const sh = ensureAccountsSheet_(ss);
  const last = sh.getLastRow();
  const have = last > 1 ? sh.getRange(2, A.LABEL, last - 1, 1).getValues().map(r => String(r[0]).trim()) : [];
  const haveSet = {}; have.forEach(h => haveSet[h] = true);
  const seen = {};
  const toAdd = [];
  rows.forEach(r => {
    const label = String(r[C.ACC - 1]).trim();
    if (!label || haveSet[label] || seen[label]) return;
    seen[label] = true;
    toAdd.push([label, String(r[C.BANK - 1]), String(r[C.NO - 1]), '', '']);
  });
  if (toAdd.length) {
    const start = Math.max(sh.getLastRow(), 1) + 1;
    sh.getRange(start, 1, toAdd.length, ACC_HEADERS.length).setValues(toAdd);
  }
  return toAdd.length > 0;
}

/**
 * Maintains each account's running balance in the Accounts tab.
 * A balance printed in the SMS always wins. When an SMS has no balance, the last known balance is
 * moved by the credit/debit amount, so the total stays current; the next SMS with a balance re-anchors it.
 */
function updateBalances_(ss, moves) {
  const sh = ss.getSheetByName(TAB.ACCOUNTS);
  const last = sh.getLastRow();
  if (last < 2) return;
  const rng = sh.getRange(2, A.BAL, last - 1, 2);
  const vals = rng.getValues();
  const nicks = sh.getRange(2, A.LABEL, last - 1, 1).getValues();
  let changed = false;
  nicks.forEach((n, i) => {
    const list = moves[String(n[0]).trim()];
    if (!list) return;
    let bal = vals[i][0] === '' || vals[i][0] === null ? null : Number(vals[i][0]);
    let at = vals[i][1] instanceof Date ? vals[i][1] : null;
    list.slice().sort((a, b) => a.dt - b.dt).forEach(m => {
      if (at && m.dt < at) return; // older than what we already know
      if (m.bal !== '') { bal = m.bal; at = m.dt; }
      else if (bal !== null && !isNaN(bal) && at) { bal = round2_(bal + m.delta); at = m.dt; }
    });
    if (bal !== null && at && (bal !== vals[i][0] || !(vals[i][1] instanceof Date) || at.getTime() !== vals[i][1].getTime())) {
      vals[i] = [bal, at]; changed = true;
    }
  });
  if (changed) rng.setValues(vals);
}

function touchDevice_(ss, device, added) {
  const sh = ensureDevicesSheet_(ss);
  const last = sh.getLastRow();
  const names = last > 1 ? sh.getRange(2, 1, last - 1, 1).getValues().map(r => String(r[0])) : [];
  const i = names.indexOf(device);
  const now = new Date();
  if (i === -1) sh.appendRow([device, now, now, added]);
  else {
    const row = i + 2;
    const total = Number(sh.getRange(row, 4).getValue()) || 0;
    sh.getRange(row, 3, 1, 2).setValues([[now, total + added]]);
  }
}

// ============================================================ ACCOUNTS (auto-discovered)

function readAccounts_(ss) {
  const sh = ensureAccountsSheet_(ss);
  const last = sh.getLastRow();
  if (last < 2) return [];
  return sh.getRange(2, 1, last - 1, ACC_HEADERS.length).getValues()
    .filter(r => String(r[A.LABEL - 1]).trim())
    .map(r => ({
      nickname: String(r[A.LABEL - 1]).trim(),
      bank: String(r[A.BANK - 1]).trim(),
      number: String(r[A.NO - 1]).replace(/[^0-9]/g, ''),
      owner: '',
      aliases: [],
      active: true,
      balance: r[A.BAL - 1] === '' ? null : Number(r[A.BAL - 1]),
      balanceAsOf: r[A.BAL_AT - 1] instanceof Date ? r[A.BAL_AT - 1].getTime() : null,
    }));
}

function publicAccount_(a) { return a; }

/** Kept for older app builds that still send "add account"; it just seeds a row (everything is auto-discovered now). */
function addAccount_(ss, acc) {
  const nick = String(acc.nickname || '').trim();
  if (!nick) throw new Error('Nickname is required');
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    if (readAccounts_(ss).some(a => a.nickname.toLowerCase() === nick.toLowerCase())) return { added: nick };
    const num = String(acc.number || '').replace(/[^0-9]/g, '');
    const masked = num.length >= 4 ? '••' + num.slice(-4) : num;
    ensureAccountsSheet_(ss).appendRow([nick, String(acc.bank || ''), masked, '', '']);
    rebuildReports();
    bumpRev_();
    return { added: nick };
  } finally { lock.releaseLock(); }
}

/** Fills the Accounts tab from every distinct account label already present in Master (so old data shows up too). */
function syncAccountsFromMaster_(ss) {
  const master = ss.getSheetByName(TAB.MASTER);
  const sh = ensureAccountsSheet_(ss);
  if (!master) return;
  const last = master.getLastRow();
  const existing = sh.getLastRow() > 1 ? sh.getRange(2, 1, sh.getLastRow() - 1, 1).getValues().map(r => String(r[0]).trim()) : [];
  const haveSet = {}; existing.forEach(h => haveSet[h] = true);
  const seen = {};
  const toAdd = [];
  if (last > 1) {
    const vals = master.getRange(2, C.ACC, last - 1, C.NO - C.ACC + 1).getValues(); // cols E (account), F (bank), G (a/c no)
    vals.forEach(r => {
      const label = String(r[0]).trim();
      if (!label || haveSet[label] || seen[label]) return;
      seen[label] = true;
      toAdd.push([label, String(r[1]), String(r[2]), '', '']);
    });
  }
  if (toAdd.length) {
    const start = Math.max(sh.getLastRow(), 1) + 1;
    sh.getRange(start, 1, toAdd.length, ACC_HEADERS.length).setValues(toAdd);
  }
}

// ============================================================ FAST READS FOR THE APP

function home_(ss) {
  const out = summary_(ss);
  out.recent = history_(ss, {});
  return out;
}

function summary_(ss) {
  const master = ensureTxnSheet_(ss, TAB.MASTER);
  const rows = Math.max(master.getLastRow() - 1, 0);
  const mKey = Utilities.formatDate(new Date(), TZ(), 'yyyy-MM');
  const props = PropertiesService.getScriptProperties().getProperties();
  return cached_('sum:' + (props.REV || '0') + ':' + rows + ':' + mKey, () => {
    const stats = loadStats_(master, rows, props);
    const yKey = mKey.slice(0, 4);
    const accounts = readAccounts_(ss).filter(a => a.active);
    const totals = { balance: 0, company: 0, personal: 0 };
    const list = accounts.map(a => {
      const m = blank_(), y = blank_(), all = blank_();
      const months = stats[a.nickname] || {};
      Object.keys(months).forEach(k => {
        const v = months[k];
        addTo_(all, v);
        if (k.slice(0, 4) === yKey) addTo_(y, v);
        if (k === mKey) addTo_(m, v);
      });
      if (a.balance !== null && !isNaN(a.balance)) {
        totals.balance += a.balance;
        totals[a.owner === 'Personal' ? 'personal' : 'company'] += a.balance;
      }
      return {
        nickname: a.nickname, bank: a.bank, owner: a.owner, masked: '••' + a.number.slice(-4),
        balance: a.balance, balanceAsOf: a.balanceAsOf,
        month: round_(m), year: round_(y), all: round_(all),
      };
    });
    Object.keys(totals).forEach(k => totals[k] = round2_(totals[k]));
    return { accounts: list, totals: totals, txnCount: rows };
  });
}

/**
 * One page of transactions, newest first — always read from Master and filtered in place.
 * params: account (label, optional), type (CREDIT|DEBIT, optional), q (search, optional),
 *         offset (row offset, default 0), limit (default 25, max 100)
 */
function history_(ss, p) {
  p = p || {};
  const nick = String(p.account || '').trim();
  const type = /^(CREDIT|DEBIT)$/i.test(String(p.type || '')) ? String(p.type).toUpperCase() : '';
  const q = String(p.q || '').trim().toLowerCase().slice(0, 60);
  const limit = Math.min(Math.max(parseInt(p.limit, 10) || PAGE, 1), 100);
  const offset = Math.max(parseInt(p.offset, 10) || 0, 0);

  const sh = ss.getSheetByName(TAB.MASTER);
  if (!sh) return { items: [], nextOffset: offset, hasMore: false, total: 0 };
  const total = Math.max(sh.getLastRow() - 1, 0);
  const rev = PropertiesService.getScriptProperties().getProperty('REV') || '0';
  const run = () => scanPage_(sh, total, nick, type, q, offset, limit);
  return (q || nick) ? run() : cached_(['h', rev, sh.getSheetId(), total, type, offset, limit].join(':'), run);
}

function scanPage_(sh, total, nick, type, q, offset, limit) {
  const W = C.SMS; // everything up to and including the original SMS
  const filtered = !!(nick || type || q);
  const CHUNK = filtered ? 500 : limit;
  const MAX_SCAN = 5000; // keeps one request well under a few seconds even on huge sheets
  const nickLower = nick.toLowerCase();
  const items = [];
  let pos = offset, scanned = 0;
  while (pos < total && items.length < limit && scanned < MAX_SCAN) {
    const data = sh.getRange(pos + 2, 1, Math.min(CHUNK, total - pos), W).getValues();
    let i = 0;
    for (; i < data.length && items.length < limit; i++) {
      const r = data[i];
      if (!String(r[C.ID - 1]).trim()) continue;
      if (nick && String(r[C.ACC - 1]).trim().toLowerCase() !== nickLower) continue;
      if (type && String(r[C.TYPE - 1]).toUpperCase() !== type) continue;
      if (q && !rowMatches_(r, q)) continue;
      items.push(rowToItem_(r));
    }
    pos += i; scanned += i;
  }
  return { items: items, nextOffset: pos, hasMore: pos < total, total: total };
}

function rowMatches_(r, q) {
  return [C.ACC, C.BANK, C.MODE, C.PARTY, C.REF, C.DEVICE, C.SMS, C.CR, C.DR]
    .some(c => String(r[c - 1]).toLowerCase().indexOf(q) !== -1);
}

function rowToItem_(r) {
  const d = r[C.DT - 1] instanceof Date ? r[C.DT - 1] : new Date(r[C.DT - 1]);
  const credit = String(r[C.TYPE - 1]).toUpperCase() === 'CREDIT';
  const bal = r[C.BAL - 1];
  return {
    id: String(r[C.ID - 1]), ts: d.getTime() || 0,
    account: String(r[C.ACC - 1]), bank: String(r[C.BANK - 1]), masked: String(r[C.NO - 1]), owner: String(r[C.OWNER - 1]),
    type: credit ? 'CREDIT' : 'DEBIT', amount: round2_(Number(credit ? r[C.CR - 1] : r[C.DR - 1]) || 0),
    balance: bal === '' || bal === null || isNaN(Number(bal)) ? null : Number(bal),
    mode: String(r[C.MODE - 1]), party: String(r[C.PARTY - 1]), ref: String(r[C.REF - 1]),
    sender: String(r[C.SENDER - 1]), device: String(r[C.DEVICE - 1]), sms: String(r[C.SMS - 1]),
  };
}

// ------------------------------------------------------------ totals index (Script Properties)
// ST:<account> = {"2026-11":[credit, debit, count], …}   ST_N = rows indexed   ST_DIRTY = '1' → rebuild

function loadStats_(master, rows, props) {
  if (props.ST_DIRTY !== '1' && props.ST_N !== undefined && Number(props.ST_N) === rows) {
    const out = {};
    Object.keys(props).forEach(k => { if (k.indexOf('ST:') === 0) out[k.slice(3)] = JSON.parse(props[k]); });
    return out;
  }
  const lock = LockService.getScriptLock();
  const locked = lock.tryLock(20000);
  try { return rebuildStats_(master, locked); }
  finally { if (locked) lock.releaseLock(); }
}

function rebuildStats_(master, save) {
  const last = master.getLastRow();
  const data = last > 1 ? master.getRange(2, C.DT, last - 1, C.DR - C.DT + 1).getValues() : [];
  const st = {};
  let n = 0;
  data.forEach(r => {
    if (!String(r[C.ACC - C.DT]).trim()) return;
    n++;
    let month = String(r[C.MONTH - C.DT] || '');
    if (!/^\d{4}-\d{2}$/.test(month)) {
      const d = r[0] instanceof Date ? r[0] : new Date(r[0]);
      if (isNaN(d)) return;
      month = Utilities.formatDate(d, TZ(), 'yyyy-MM');
    }
    statAdd_(st, String(r[C.ACC - C.DT]).trim(), month, r[C.CR - C.DT], r[C.DR - C.DT]);
  });
  if (save) {
    const props = PropertiesService.getScriptProperties();
    const obj = { ST_N: String(data.length), ST_DIRTY: '0' };
    Object.keys(st).forEach(k => obj['ST:' + k] = JSON.stringify(st[k]));
    Object.keys(props.getProperties()).forEach(k => { if (k.indexOf('ST:') === 0 && !(k in obj)) props.deleteProperty(k); });
    props.setProperties(obj);
  }
  return st;
}

function addToStats_(rows, rowsBefore) {
  const props = PropertiesService.getScriptProperties();
  const all = props.getProperties();
  if (all.ST_DIRTY === '1' || all.ST_N === undefined || Number(all.ST_N) !== rowsBefore) {
    props.setProperty('ST_DIRTY', '1');
    return;
  }
  const st = {};
  rows.forEach(r => {
    const nick = String(r[C.ACC - 1]);
    if (!st[nick]) st[nick] = all['ST:' + nick] ? JSON.parse(all['ST:' + nick]) : {};
    statAdd_(st, nick, r[C.MONTH - 1], r[C.CR - 1], r[C.DR - 1]);
  });
  const obj = { ST_N: String(rowsBefore + rows.length) };
  Object.keys(st).forEach(k => obj['ST:' + k] = JSON.stringify(st[k]));
  props.setProperties(obj);
}

function statAdd_(st, nick, month, cr, dr) {
  const m = st[nick] = st[nick] || {};
  const v = m[month] = m[month] || [0, 0, 0];
  v[0] = round2_(v[0] + (Number(cr) || 0));
  v[1] = round2_(v[1] + (Number(dr) || 0));
  v[2]++;
}

/** Menu: rebuilds the app's totals from the Master tab (use after editing or deleting rows by hand). */
function recalcTotals() {
  const ss = SpreadsheetApp.getActive();
  rebuildStats_(ensureTxnSheet_(ss, TAB.MASTER), true);
  bumpRev_();
  try { SpreadsheetApp.getActive().toast('App totals recalculated', 'LedgerSync'); } catch (e) {}
}

/** Any hand edit in the sheet: refresh the app's cached replies, and re-index totals if Master changed. */
function onEdit(e) {
  try {
    const name = e && e.range ? e.range.getSheet().getName() : '';
    if (name === TAB.DASH || name === TAB.MONTHLY || name === TAB.DEVICES || name === TAB.FILTER) return;
    const obj = { REV: String(Date.now()) };
    if (name === TAB.MASTER) obj.ST_DIRTY = '1';
    PropertiesService.getScriptProperties().setProperties(obj);
  } catch (err) { /* never block an edit */ }
}

/** Installable trigger: runs the Filter tab's "Export to Excel / PDF" checkbox buttons. */
function onEditInstallable(e) {
  try {
    if (!e || !e.range) return;
    const sh = e.range.getSheet();
    if (sh.getName() !== TAB.FILTER) return;
    if (String(e.value) !== 'TRUE') return;
    const r = e.range.getRow(), c = e.range.getColumn();
    if (r === F.XLS_ROW && c === F.XLS_COL) { e.range.setValue(false); runExport_('xlsx'); }
    else if (r === F.PDF_ROW && c === F.PDF_COL) { e.range.setValue(false); runExport_('pdf'); }
  } catch (err) {
    try { SpreadsheetApp.getActive().toast('Export failed: ' + (err && err.message || err), 'LedgerSync'); } catch (e2) {}
  }
}

function bumpRev_() { PropertiesService.getScriptProperties().setProperty('REV', String(Date.now())); }

function cached_(key, fn) {
  let cache = null;
  try { cache = CacheService.getScriptCache(); const hit = cache.get(key); if (hit) return JSON.parse(hit); } catch (e) {}
  const out = fn();
  try { const s = JSON.stringify(out); if (cache && s.length < 95000) cache.put(key, s, CACHE_TTL); } catch (e) {}
  return out;
}

function blank_() { return { credit: 0, debit: 0, count: 0 }; }
function addTo_(b, v) { b.credit += Number(v[0]) || 0; b.debit += Number(v[1]) || 0; b.count += Number(v[2]) || 0; }
function round_(b) { return { credit: round2_(b.credit), debit: round2_(b.debit), count: b.count }; }
function round2_(n) { return Math.round(n * 100) / 100; }
function maskNo_(n) { n = String(n || ''); return n.length > 4 ? 'XX' + n.slice(-4) : n; }

// ============================================================ EXPORT (Excel / PDF of the current filter)

function exportFilterToExcel() { runExport_('xlsx'); }
function exportFilterToPdf() { runExport_('pdf'); }

function runExport_(format) {
  const ss = SpreadsheetApp.getActive();
  const fs = ss.getSheetByName(TAB.FILTER);
  if (!fs) { toast_('Open the Filter tab first (LedgerSync ▸ Rebuild).'); return; }
  const data = filterRows_(fs);
  const info = filterSelection_(fs);

  const temp = SpreadsheetApp.create('LedgerSync filter ' + Utilities.formatDate(new Date(), TZ(), 'yyyy-MM-dd HH-mm-ss'));
  try {
    const sh = temp.getSheets()[0];
    sh.setName('Filtered');
    sh.getRange(1, 1).setValue('LedgerSync — filtered transactions').setFontWeight('bold').setFontSize(14);
    sh.getRange(2, 1).setValue(info).setFontColor('#555555');
    const hr = 4;
    sh.getRange(hr, 1, 1, data.headers.length).setValues([data.headers])
      .setFontWeight('bold').setBackground(COLORS.header).setFontColor('#FFFFFF');
    if (data.rows.length) sh.getRange(hr + 1, 1, data.rows.length, data.headers.length).setValues(data.rows);
    sh.setFrozenRows(hr);
    [150, 150, 110, 80, 80, 110, 110, 120, 90, 180, 150, 130].forEach((w, i) => { if (i < data.headers.length) sh.setColumnWidth(i + 1, w); });
    SpreadsheetApp.flush();
    const file = convertAndSave_(temp.getId(), format, data.rows.length);
    showExportLink_(file, format, data.rows.length);
  } finally {
    try { DriveApp.getFileById(temp.getId()).setTrashed(true); } catch (e) {}
  }
}

function convertAndSave_(id, format, count) {
  const pdf = format === 'pdf';
  const token = ScriptApp.getOAuthToken();
  const params = pdf
    ? 'format=pdf&portrait=true&fitw=true&gridlines=true&size=A4&sheetnames=false&printtitle=false&pagenumbers=false&top_margin=0.4&bottom_margin=0.4&left_margin=0.4&right_margin=0.4'
    : 'format=xlsx';
  const url = 'https://docs.google.com/spreadsheets/d/' + id + '/export?' + params;
  const res = UrlFetchApp.fetch(url, { headers: { Authorization: 'Bearer ' + token }, muteHttpExceptions: true });
  if (res.getResponseCode() >= 300) throw new Error('Google export returned HTTP ' + res.getResponseCode());
  const stamp = Utilities.formatDate(new Date(), TZ(), 'yyyyMMdd-HHmm');
  const blob = res.getBlob().setName('LedgerSync-filter-' + stamp + (pdf ? '.pdf' : '.xlsx'));
  return exportFolder_().createFile(blob);
}

function exportFolder_() {
  const name = 'LedgerSync Exports';
  const it = DriveApp.getFoldersByName(name);
  return it.hasNext() ? it.next() : DriveApp.createFolder(name);
}

/** Reads the current results from the Filter tab (the QUERY output under the controls). */
function filterRows_(fs) {
  const stdHeaders = ['Date & Time', 'Account', 'Bank', 'A/c', 'Type', 'Credit (₹)', 'Debit (₹)', 'Balance (₹)', 'Mode', 'Party', 'Reference', 'Device'];
  const last = fs.getLastRow();
  const hr = F.RESULT_ROW;
  const firstCell = String(fs.getRange(hr, 1).getDisplayValue()).toLowerCase();
  if (last <= hr || firstCell.indexOf('no matching') !== -1 || firstCell === '') {
    return { headers: stdHeaders, rows: [] };
  }
  const headers = fs.getRange(hr, 1, 1, F.RESULT_COLS).getDisplayValues()[0].map((h, i) => String(h).trim() || stdHeaders[i]);
  const raw = fs.getRange(hr + 1, 1, last - hr, F.RESULT_COLS).getValues()
    .filter(r => String(r[0]).trim() !== '' && String(r[0]).toLowerCase().indexOf('no matching') === -1);
  return { headers: headers, rows: raw };
}

function filterSelection_(fs) {
  const v = fs.getRange(4, 2, 5, 1).getDisplayValues().map(r => String(r[0]).trim());
  const parts = ['Account: ' + (v[0] || 'All'), 'Bank: ' + (v[1] || 'All'), 'Type: ' + (v[2] || 'All')];
  if (v[3]) parts.push('From: ' + v[3]);
  if (v[4]) parts.push('To: ' + v[4]);
  return 'Filter — ' + parts.join('   ·   ') + '     (exported ' + Utilities.formatDate(new Date(), TZ(), 'dd-MMM-yyyy hh:mm a') + ')';
}

function showExportLink_(file, format, count) {
  const url = file.getUrl();
  const label = format === 'pdf' ? 'PDF' : 'Excel (.xlsx)';
  const html = HtmlService.createHtmlOutput(
    '<div style="font-family:Arial;font-size:14px;line-height:1.6">' +
    '<p>Your <b>' + label + '</b> export is ready — ' + count + ' transaction' + (count === 1 ? '' : 's') + '.</p>' +
    '<p><a href="' + url + '" target="_blank" style="background:#6D4BFF;color:#fff;padding:10px 18px;border-radius:8px;text-decoration:none;display:inline-block">Open / download</a></p>' +
    '<p style="color:#777">Also saved in your Google Drive ▸ <b>LedgerSync Exports</b>.</p></div>'
  ).setWidth(420).setHeight(210);
  try { SpreadsheetApp.getUi().showModalDialog(html, 'Export ready'); }
  catch (e) { toast_('Export saved to Drive ▸ LedgerSync Exports'); }
}

function toast_(msg) { try { SpreadsheetApp.getActive().toast(msg, 'LedgerSync'); } catch (e) {} }

// ============================================================ SHEET BUILDERS

function sheetNameFor_(nick) {
  let name = String(nick).replace(/[\[\]\*\?\/\\:]/g, ' ').trim().slice(0, 90);
  if (Object.values(TAB).indexOf(name) !== -1) name += ' (A/c)';
  return name || 'Account';
}

function ensureTxnSheet_(ss, name) {
  let sh = ss.getSheetByName(name);
  if (sh) return sh;
  sh = ss.insertSheet(name);
  sh.getRange(1, 1, 1, TXN_HEADERS.length).setValues([TXN_HEADERS])
    .setFontWeight('bold').setBackground(COLORS.header).setFontColor(COLORS.headerText).setVerticalAlignment('middle');
  sh.setRowHeight(1, 34);
  sh.setFrozenRows(1);
  sh.setFrozenColumns(2);
  const rows = sh.getMaxRows() - 1;
  sh.getRange(2, C.DT, rows, 1).setNumberFormat('dd-MMM-yyyy  hh:mm AM/PM');
  sh.getRange(2, C.MONTH, rows, 1).setNumberFormat('@');
  sh.getRange(2, C.CR, rows, 3).setNumberFormat(FMT);
  sh.getRange(2, C.SYNCED, rows, 1).setNumberFormat('dd-MMM-yy hh:mm');
  sh.getRange(2, C.CR, rows, 1).setFontColor(COLORS.credit).setFontWeight('bold');
  sh.getRange(2, C.DR, rows, 1).setFontColor(COLORS.debit).setFontWeight('bold');
  [150, 165, 70, 55, 150, 110, 80, 80, 70, 110, 110, 120, 90, 180, 150, 100, 130, 420, 120]
    .forEach((w, i) => sh.setColumnWidth(i + 1, w));
  sh.hideColumns(C.ID);
  sh.getRange(1, 1, sh.getMaxRows(), TXN_HEADERS.length).applyRowBanding(SpreadsheetApp.BandingTheme.INDIGO, true, false);
  const rules = [
    SpreadsheetApp.newConditionalFormatRule().whenTextEqualTo('CREDIT').setFontColor(COLORS.credit).setBold(true).setRanges([sh.getRange(2, C.TYPE, rows, 1)]).build(),
    SpreadsheetApp.newConditionalFormatRule().whenTextEqualTo('DEBIT').setFontColor(COLORS.debit).setBold(true).setRanges([sh.getRange(2, C.TYPE, rows, 1)]).build(),
  ];
  sh.setConditionalFormatRules(rules);
  sh.getRange(2, C.SMS, rows, 1).setWrap(false);
  return sh;
}

function ensureAccountsSheet_(ss) {
  let sh = ss.getSheetByName(TAB.ACCOUNTS);
  // Migrate an old hand-maintained "Accounts" tab (header "Nickname") out of the way, then build the auto one.
  if (sh && String(sh.getRange(1, 1).getValue()).trim().toLowerCase() === 'nickname') {
    try { sh.setName('Accounts (old tagging)'); } catch (e) { try { sh.setName('Accounts old ' + Date.now()); } catch (e2) {} }
    sh = ss.getSheetByName(TAB.ACCOUNTS);
  }
  if (sh) return sh;
  sh = ss.insertSheet(TAB.ACCOUNTS);
  sh.getRange(1, 1, 1, ACC_HEADERS.length).setValues([ACC_HEADERS])
    .setFontWeight('bold').setBackground(COLORS.header).setFontColor(COLORS.headerText).setWrap(true).setVerticalAlignment('middle');
  sh.setRowHeight(1, 40);
  sh.setFrozenRows(1);
  [230, 150, 120, 150, 180].forEach((w, i) => sh.setColumnWidth(i + 1, w));
  const rows = sh.getMaxRows() - 1;
  sh.getRange(2, A.NO, rows, 1).setNumberFormat('@');
  sh.getRange(2, A.BAL, rows, 1).setNumberFormat(FMT);
  sh.getRange(2, A.BAL_AT, rows, 1).setNumberFormat('dd-MMM-yyyy hh:mm AM/PM');
  sh.getRange('A2').setNote('This list fills in automatically. Every bank account that sends a debit/credit SMS to a connected phone appears here with its latest balance. No manual entry needed.');
  return sh;
}

function ensureDevicesSheet_(ss) {
  let sh = ss.getSheetByName(TAB.DEVICES);
  if (sh) return sh;
  sh = ss.insertSheet(TAB.DEVICES);
  sh.getRange(1, 1, 1, 4).setValues([['Device', 'First Seen', 'Last Sync', 'Entries Added']])
    .setFontWeight('bold').setBackground(COLORS.header).setFontColor(COLORS.headerText);
  sh.setFrozenRows(1);
  sh.getRange('B2:C').setNumberFormat('dd-MMM-yyyy hh:mm AM/PM');
  [200, 170, 170, 120].forEach((w, i) => sh.setColumnWidth(i + 1, w));
  return sh;
}

// ============================================================ REPORTS (live formulas)

function rebuildReports() {
  const ss = SpreadsheetApp.getActive();
  ensureTxnSheet_(ss, TAB.MASTER);
  ensureAccountsSheet_(ss);
  syncAccountsFromMaster_(ss);
  buildDashboard_(ss);
  buildMonthly_(ss);
  buildFilter_(ss);
  orderTabs_(ss);
  bumpRev_();
}

function buildDashboard_(ss) {
  let sh = ss.getSheetByName(TAB.DASH);
  if (!sh) sh = ss.insertSheet(TAB.DASH, 0);
  sh.clear(); sh.clearConditionalFormatRules();
  sh.getBandings().forEach(b => b.remove());
  sh.setHiddenGridlines(true);
  const accounts = readAccounts_(ss).filter(a => a.active);
  const M = "'" + TAB.MASTER + "'!";
  const ACC = "'" + TAB.ACCOUNTS + "'!";

  sh.getRange('A1').setValue('LedgerSync · Bank Dashboard').setFontSize(20).setFontWeight('bold').setFontColor(COLORS.header);
  sh.getRange('A2').setFormula('="Live · updated "&TEXT(NOW(),"dd mmm yyyy, hh:mm AM/PM")&"  ·  "&TEXT(TODAY(),"mmmm yyyy")')
    .setFontColor('#64748B');

  const head = ['Account', 'Bank', 'A/c', 'Current Balance',
    'This Month · Credit', 'This Month · Debit', 'This Month · Net',
    'This Year · Credit', 'This Year · Debit', 'This Year · Net',
    'All-time · Credit', 'All-time · Debit', 'All-time · Net', 'Last Transaction'];
  const H = 4;
  sh.getRange(H, 1, 1, head.length).setValues([head]).setFontWeight('bold').setBackground(COLORS.header)
    .setFontColor('#FFFFFF').setWrap(true).setVerticalAlignment('middle').setHorizontalAlignment('center');
  sh.setRowHeight(H, 44);

  const mStart = 'EOMONTH(TODAY(),-1)+1';
  const yStart = 'DATE(YEAR(TODAY()),1,1)';
  const f = [];
  accounts.forEach((a, i) => {
    const r = H + 1 + i;
    const key = '$A' + r;
    const sumIf = (col, extra) => 'SUMIFS(' + M + col + ':' + col + ',' + M + '$E:$E,' + key + (extra || '') + ')';
    const cr = 'J', dr = 'K';
    const mth = ',' + M + '$B:$B,">="&' + mStart;
    const yr = ',' + M + '$B:$B,">="&' + yStart;
    f.push([
      a.nickname, a.bank, '••' + a.number.slice(-4),
      '=IFERROR(INDEX(' + ACC + '$D:$D,MATCH(' + key + ',' + ACC + '$A:$A,0)),"")',
      '=' + sumIf(cr, mth), '=' + sumIf(dr, mth), '=E' + r + '-F' + r,
      '=' + sumIf(cr, yr), '=' + sumIf(dr, yr), '=H' + r + '-I' + r,
      '=' + sumIf(cr), '=' + sumIf(dr), '=K' + r + '-L' + r,
      '=IF(COUNTIF(' + M + '$E:$E,' + key + ')=0,"",MAXIFS(' + M + '$B:$B,' + M + '$E:$E,' + key + '))',
    ]);
  });
  const n = accounts.length;
  if (n) sh.getRange(H + 1, 1, n, head.length).setValues(f);

  // Grand total row
  const tStart = H + 1 + n + 1;
  const first = H + 1, lastR = Math.max(H + n, H + 1);
  {
    const r = tStart;
    const row = ['GRAND TOTAL', '', ''];
    for (let c = 4; c <= 13; c++) {
      const col = colLetter_(c);
      row.push(n ? '=SUM(' + col + first + ':' + col + lastR + ')' : 0);
    }
    row.push('');
    sh.getRange(r, 1, 1, head.length).setValues([row]).setFontWeight('bold')
      .setBackground(COLORS.accent).setFontColor('#FFFFFF');
  }

  const all = sh.getRange(H + 1, 4, n + 2, 10);
  all.setNumberFormat(FMT_NET);
  if (n) {
    sh.getRange(H + 1, 14, n, 1).setNumberFormat('dd-MMM-yy hh:mm AM/PM');
    sh.getRange(H + 1, 1, n, head.length).applyRowBanding(SpreadsheetApp.BandingTheme.LIGHT_GREY, false, false);
    [5, 8, 11].forEach(c => sh.getRange(H + 1, c, n, 1).setFontColor(COLORS.credit));
    [6, 9, 12].forEach(c => sh.getRange(H + 1, c, n, 1).setFontColor(COLORS.debit));
    sh.getRange(H + 1, 1, n, 1).setFontWeight('bold');
  } else {
    sh.getRange(H + 1, 1).setValue('No transactions yet. As soon as a bank SMS is captured on a connected phone, its account appears here.').setFontColor('#64748B');
  }
  sh.setColumnWidth(1, 200); sh.setColumnWidth(2, 130); sh.setColumnWidth(3, 70); sh.setColumnWidth(4, 130);
  for (let c = 5; c <= 13; c++) sh.setColumnWidth(c, 125);
  sh.setColumnWidth(14, 150);
  sh.setFrozenRows(H);
  sh.setFrozenColumns(1);
}

function buildMonthly_(ss) {
  let sh = ss.getSheetByName(TAB.MONTHLY);
  if (!sh) sh = ss.insertSheet(TAB.MONTHLY, 1);
  sh.clear();
  sh.setHiddenGridlines(true);
  const M = "'" + TAB.MASTER + "'!A2:S";
  sh.getRange('A1').setValue('Month-wise totals (all accounts)').setFontWeight('bold').setFontSize(14).setFontColor(COLORS.header);
  sh.getRange('A2').setFormula('=IFERROR(QUERY(' + M + ',"select C, sum(J), sum(K), sum(J)-sum(K), count(A) where C is not null group by C order by C desc label C \'Month\', sum(J) \'Total Credit\', sum(K) \'Total Debit\', sum(J)-sum(K) \'Net\', count(A) \'Txns\'",0),"No data yet")');
  sh.getRange('H1').setValue('Month-wise by account').setFontWeight('bold').setFontSize(14).setFontColor(COLORS.header);
  sh.getRange('H2').setFormula('=IFERROR(QUERY(' + M + ',"select C, E, sum(J), sum(K), sum(J)-sum(K), count(A) where C is not null group by C, E order by C desc, E label C \'Month\', E \'Account\', sum(J) \'Credit\', sum(K) \'Debit\', sum(J)-sum(K) \'Net\', count(A) \'Txns\'",0),"No data yet")');
  sh.getRange('P1').setValue('Year-wise by account').setFontWeight('bold').setFontSize(14).setFontColor(COLORS.header);
  sh.getRange('P2').setFormula('=IFERROR(QUERY(' + M + ',"select D, E, sum(J), sum(K), sum(J)-sum(K) where D is not null group by D, E order by D desc, E label D \'Year\', E \'Account\', sum(J) \'Credit\', sum(K) \'Debit\', sum(J)-sum(K) \'Net\'",0),"No data yet")');
  sh.getRange('B3:D').setNumberFormat(FMT_NET);
  sh.getRange('J3:L').setNumberFormat(FMT_NET);
  sh.getRange('R3:T').setNumberFormat(FMT_NET);
  ['A2:E2', 'H2:M2', 'P2:T2'].forEach(a => sh.getRange(a).setFontWeight('bold').setBackground(COLORS.header).setFontColor('#FFFFFF'));
  sh.setColumnWidth(1, 90); sh.setColumnWidth(8, 90); sh.setColumnWidth(9, 190); sh.setColumnWidth(16, 70); sh.setColumnWidth(17, 190);
  [2, 3, 4, 10, 11, 12, 18, 19, 20].forEach(c => sh.setColumnWidth(c, 125));
  sh.setColumnWidth(7, 30); sh.setColumnWidth(15, 30);
  sh.setFrozenRows(2);
}

// ------------------------------------------------------------ Filter & Export tab

function buildFilter_(ss) {
  let sh = ss.getSheetByName(TAB.FILTER);
  if (!sh) sh = ss.insertSheet(TAB.FILTER, 3);
  sh.clear();
  sh.getRange(1, 1, sh.getMaxRows(), sh.getMaxColumns()).clearDataValidations();
  sh.setHiddenGridlines(true);

  const M = "'" + TAB.MASTER + "'!";

  sh.getRange('A1').setValue('LedgerSync · Filter & Export').setFontSize(18).setFontWeight('bold').setFontColor(COLORS.header);
  sh.getRange('A2').setValue('Pick any mix of account, bank, type and date range. The table and totals update instantly. Then tick a box on the right (or use the LedgerSync menu) to export.')
    .setFontColor('#64748B');

  // ---- helper lists (hidden, columns P / Q) ----
  const accounts = readAccounts_(ss).filter(a => a.active).map(a => a.nickname);
  const banks = [];
  readAccounts_(ss).forEach(a => { if (a.bank && banks.indexOf(a.bank) === -1) banks.push(a.bank); });
  const accList = ['All'].concat(accounts);
  const bankList = ['All'].concat(banks);
  sh.getRange(1, F.LIST_ACC, accList.length, 1).setValues(accList.map(v => [v]));
  sh.getRange(1, F.LIST_BANK, bankList.length, 1).setValues(bankList.map(v => [v]));

  // ---- controls ----
  const labels = [['Account'], ['Bank'], ['Type'], ['From date'], ['To date']];
  sh.getRange(4, 1, 5, 1).setValues(labels).setFontWeight('bold').setFontColor('#334155');
  sh.getRange(4, 2).setValue('All');
  sh.getRange(5, 2).setValue('All');
  sh.getRange(6, 2).setValue('All');
  sh.getRange(7, 2).setValue('');
  sh.getRange(8, 2).setValue('');
  sh.getRange(7, 2, 2, 1).setNumberFormat('dd-MMM-yyyy');
  sh.getRange(4, 2, 5, 1).setBackground('#F8FAFC').setFontColor('#0F172A')
    .setBorder(true, true, true, true, true, true, '#CBD5E1', SpreadsheetApp.BorderStyle.SOLID);

  const dv = (col) => SpreadsheetApp.newDataValidation().requireValueInRange(sh.getRange(1, col, (col === F.LIST_ACC ? accList.length : bankList.length), 1), true).setAllowInvalid(false).build();
  sh.getRange(F.ACC).setDataValidation(dv(F.LIST_ACC));
  sh.getRange(F.BANK).setDataValidation(dv(F.LIST_BANK));
  sh.getRange(F.TYPE).setDataValidation(SpreadsheetApp.newDataValidation().requireValueInList(['All', 'CREDIT', 'DEBIT'], true).setAllowInvalid(false).build());

  // ---- totals box (D4:E7) ----
  const b4 = '$B$4', b5 = '$B$5', b6 = '$B$6', b7 = '$B$7', b8 = '$B$8';
  const cond =
    '((' + M + '$E$2:$E=' + b4 + ')+(' + b4 + '="All"))>0,' +
    '((' + M + '$F$2:$F=' + b5 + ')+(' + b5 + '="All"))>0,' +
    '((' + M + '$I$2:$I=' + b6 + ')+(' + b6 + '="All"))>0,' +
    '(' + M + '$B$2:$B>=IF(' + b7 + '="",DATE(1900,1,1),' + b7 + ')),' +
    '(' + M + '$B$2:$B<IF(' + b8 + '="",DATE(2999,1,1),' + b8 + '+1))';
  sh.getRange('D4').setValue('Credit (₹)').setFontWeight('bold').setFontColor('#334155');
  sh.getRange('E4').setFormula('=IFERROR(SUMPRODUCT(' + cond + ',N(' + M + '$J$2:$J)),0)');
  sh.getRange('D5').setValue('Debit (₹)').setFontWeight('bold').setFontColor('#334155');
  sh.getRange('E5').setFormula('=IFERROR(SUMPRODUCT(' + cond + ',N(' + M + '$K$2:$K)),0)');
  sh.getRange('D6').setValue('Net (₹)').setFontWeight('bold').setFontColor('#334155');
  sh.getRange('E6').setFormula('=E4-E5');
  sh.getRange('D7').setValue('Transactions').setFontWeight('bold').setFontColor('#334155');
  sh.getRange('E7').setFormula('=IFERROR(SUMPRODUCT(' + cond + '),0)');
  sh.getRange('E4:E6').setNumberFormat(FMT_NET);
  sh.getRange('E7').setNumberFormat('#,##0');
  sh.getRange('D4:E7').setBorder(true, true, true, true, true, true, '#E2E8F0', SpreadsheetApp.BorderStyle.SOLID);
  sh.getRange('E4').setFontColor(COLORS.credit).setFontWeight('bold');
  sh.getRange('E5').setFontColor(COLORS.debit).setFontWeight('bold');

  // ---- export "buttons" (checkboxes at G4 / G6) ----
  sh.getRange(F.XLS_ROW, F.XLS_COL).insertCheckboxes().setValue(false);
  sh.getRange(F.XLS_ROW, F.XLS_COL + 1).setValue('⬇  Export to Excel (.xlsx)').setFontWeight('bold').setFontColor('#0F766E');
  sh.getRange(F.PDF_ROW, F.PDF_COL).insertCheckboxes().setValue(false);
  sh.getRange(F.PDF_ROW, F.PDF_COL + 1).setValue('⬇  Export to PDF').setFontWeight('bold').setFontColor('#9A3412');
  sh.getRange(5, F.XLS_COL + 1).setValue('(tick a box to download the filtered rows)').setFontColor('#94A3B8').setFontSize(9);
  sh.getRange(7, F.PDF_COL + 1).setValue('exports exactly what the filter shows').setFontColor('#94A3B8').setFontSize(9);

  // ---- results (QUERY under the controls) ----
  sh.getRange('A10').setValue('Matching transactions').setFontWeight('bold').setFontSize(12).setFontColor(COLORS.header);
  const q =
    '"select B,E,F,G,I,J,K,L,M,N,O,Q where B is not null"' +
    '&IF(' + b4 + '="All",""," and E = \'"&' + b4 + '&"\'")' +
    '&IF(' + b5 + '="All",""," and F = \'"&' + b5 + '&"\'")' +
    '&IF(' + b6 + '="All",""," and I = \'"&' + b6 + '&"\'")' +
    '&IF(' + b7 + '=""," "," and toDate(B) >= date \'"&TEXT(' + b7 + ',"yyyy-MM-dd")&"\'")' +
    '&IF(' + b8 + '=""," "," and toDate(B) <= date \'"&TEXT(' + b8 + ',"yyyy-MM-dd")&"\'")' +
    '&" order by B desc label B \'Date & Time\', E \'Account\', F \'Bank\', G \'A/c\', I \'Type\', J \'Credit (₹)\', K \'Debit (₹)\', L \'Balance (₹)\', M \'Mode\', N \'Party\', O \'Reference\', Q \'Device\'"';
  sh.getRange(F.RESULT_ROW, 1).setFormula('=IFERROR(QUERY(' + M + 'A1:S,' + q + ',1),"No matching transactions")');
  sh.getRange(F.RESULT_ROW, 1, 1, F.RESULT_COLS).setFontWeight('bold').setBackground(COLORS.header).setFontColor('#FFFFFF');
  const fmtRows = Math.max(sh.getMaxRows() - F.RESULT_ROW, 1);
  sh.getRange(F.RESULT_ROW + 1, 6, fmtRows, 3).setNumberFormat(FMT);   // Credit / Debit / Balance columns
  sh.getRange(F.RESULT_ROW + 1, 1, fmtRows, 1).setNumberFormat('dd-MMM-yyyy  hh:mm AM/PM');

  [150, 160, 110, 80, 80, 110, 110, 120, 90, 170, 150, 120].forEach((w, i) => sh.setColumnWidth(i + 1, w));
  sh.hideColumns(F.LIST_ACC, 2); // hide the helper lists (P, Q)
  sh.setFrozenRows(F.RESULT_ROW);
  sh.setColumnWidth(F.XLS_COL + 1, 240);
}

function resortAll() {
  const ss = SpreadsheetApp.getActive();
  ss.getSheets().forEach(sh => {
    if (sh.getRange(1, 1).getValue() !== TXN_HEADERS[0]) return;
    const last = sh.getLastRow();
    if (last > 2) sh.getRange(2, 1, last - 1, TXN_HEADERS.length).sort({ column: C.DT, ascending: false });
  });
  bumpRev_();
}

function orderTabs_(ss) {
  try { orderTabsInner_(ss); } catch (e) { /* cosmetic only */ }
}

function orderTabsInner_(ss) {
  [TAB.DASH, TAB.MONTHLY, TAB.MASTER, TAB.FILTER].forEach((name, i) => {
    const sh = ss.getSheetByName(name);
    if (sh) { ss.setActiveSheet(sh); ss.moveActiveSheet(i + 1); }
  });
  const acc = ss.getSheetByName(TAB.ACCOUNTS), dev = ss.getSheetByName(TAB.DEVICES);
  if (acc) { ss.setActiveSheet(acc); ss.moveActiveSheet(ss.getSheets().length); }
  if (dev) { ss.setActiveSheet(dev); ss.moveActiveSheet(ss.getSheets().length); }
  const dash = ss.getSheetByName(TAB.DASH);
  if (dash) ss.setActiveSheet(dash);
}

function colLetter_(c) { let s = ''; while (c > 0) { const m = (c - 1) % 26; s = String.fromCharCode(65 + m) + s; c = Math.floor((c - 1) / 26); } return s; }

/** Rebuilds Dashboard + Filter when the account list changed (e.g. a new account was detected). */
function syncReportsIfAccountsChanged_(ss) {
  const sig = readAccounts_(ss).map(a => a.nickname).join('|');
  const props = PropertiesService.getScriptProperties();
  if (props.getProperty('ACC_SIG') === sig) return;
  rebuildReports();
  props.setProperty('ACC_SIG', sig);
}
