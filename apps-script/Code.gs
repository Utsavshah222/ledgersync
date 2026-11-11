/**
 * LedgerSync — Google Sheets backend
 * ------------------------------------------------------------
 * Paste this whole file into  Extensions ▸ Apps Script  of the company spreadsheet,
 * run  setup  once, then  Deploy ▸ New deployment ▸ Web app  (Execute as: Me, Access: Anyone).
 *
 * Tabs it maintains:
 *   Dashboard  – live balances + This Month / This Year / All-time credit, debit, net per account
 *   Monthly    – month-by-month totals (overall and per account)
 *   Master     – every transaction from every account, newest first
 *   <Account>  – one tab per tracked account, same columns as Master
 *   Accounts   – the list of accounts to track (edit here or from the app)
 *   Devices    – which phones are sending data and when they last synced
 */

const TAB = { DASH: 'Dashboard', MONTHLY: 'Monthly', MASTER: 'Master', ACCOUNTS: 'Accounts', DEVICES: 'Devices' };

const TXN_HEADERS = ['Txn ID', 'Date & Time', 'Month', 'Year', 'Account', 'Bank', 'A/c No.', 'Owner', 'Type',
  'Credit (₹)', 'Debit (₹)', 'Balance After (₹)', 'Mode', 'Party / Merchant', 'Reference', 'Sender', 'Device', 'Original SMS', 'Synced At'];
const C = { ID: 1, DT: 2, MONTH: 3, YEAR: 4, ACC: 5, BANK: 6, NO: 7, OWNER: 8, TYPE: 9, CR: 10, DR: 11, BAL: 12, MODE: 13, PARTY: 14, REF: 15, SENDER: 16, DEVICE: 17, SMS: 18, SYNCED: 19 };

const ACC_HEADERS = ['Nickname', 'Bank', 'Account Number', 'Owner (Company/Personal)', 'Linked Card Last-4 (comma sep.)', 'Paused (tick to stop tracking)', 'Last Balance (₹)', 'Balance As Of', 'Notes'];
const A = { NICK: 1, BANK: 2, NUM: 3, OWNER: 4, ALIASES: 5, PAUSED: 6, BAL: 7, BAL_AT: 8 };

const FMT = '"₹"#,##0.00';
const FMT_NET = '"₹"#,##0.00;[Red]-"₹"#,##0.00';

let tz_ = null;
const TZ = () => tz_ || (tz_ = Session.getScriptTimeZone() || 'Asia/Kolkata');

const API_VERSION = 2;
const PAGE = 25;            // default page size for the app's history lists
const CACHE_TTL = 600;      // seconds; every write bumps REV so cached replies never go stale
const COLORS = { header: '#1E1B4B', headerText: '#FFFFFF', credit: '#0F9D58', debit: '#D93025', band: '#F5F3FF', accent: '#6D4BFF' };

// ============================================================ MENU / SETUP

function onOpen() {
  SpreadsheetApp.getUi().createMenu('LedgerSync')
    .addItem('Connection details (for the app)', 'showConnection')
    .addItem('Rebuild Dashboard & Monthly', 'rebuildReports')
    .addItem('Re-sort all sheets (newest first)', 'resortAll')
    .addItem('Recalculate app totals (after hand edits)', 'recalcTotals')
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
  orderTabs_(ss);
  const first = ss.getSheetByName('Sheet1');
  if (first && first.getLastRow() === 0 && ss.getSheets().length > 1) ss.deleteSheet(first);

  Logger.log('Setup complete. Secret key: ' + props.getProperty('SECRET'));
  try { showConnection(); } catch (e) { /* run from editor: no UI */ }
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

  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const master = ensureTxnSheet_(ss, TAB.MASTER);
    const before = Math.max(master.getLastRow() - 1, 0);
    const existing = new Set(readIds_(master));
    const accounts = readAccounts_(ss);
    const byNick = {};
    accounts.forEach(a => byNick[a.nickname.toLowerCase()] = a);

    const now = new Date();
    const rows = [];
    const perAccount = {};
    const moves = {};

    txns.forEach(t => {
      const id = String(t.id || '').trim();
      if (!id) return;
      if (existing.has(id)) { results[id] = 'duplicate'; return; }
      existing.add(id);

      const acc = byNick[String(t.account || '').toLowerCase()];
      const nick = acc ? acc.nickname : String(t.account || 'Unknown');
      const dt = new Date(Number(t.ts) || Date.now());
      const credit = t.type === 'CREDIT';
      const amount = round2_(Number(t.amount) || 0);
      const bal = (t.balance === null || t.balance === undefined || t.balance === '') ? '' : round2_(Number(t.balance));
      const row = [
        id, dt,
        Utilities.formatDate(dt, TZ(), 'yyyy-MM'),
        Number(Utilities.formatDate(dt, TZ(), 'yyyy')),
        nick,
        acc ? acc.bank : String(t.bank || ''),
        acc ? maskNo_(acc.number) : String(t.masked || ''),
        acc ? acc.owner : String(t.owner || 'Company'),
        credit ? 'CREDIT' : 'DEBIT',
        credit ? amount : '',
        credit ? '' : amount,
        bal,
        String(t.mode || ''), String(t.party || ''), String(t.ref || ''),
        String(t.sender || ''), device, String(t.sms || '').slice(0, 1000), now,
      ];
      rows.push(row);
      (perAccount[nick] = perAccount[nick] || []).push(row);
      (moves[nick] = moves[nick] || []).push({ dt: dt, delta: credit ? amount : -amount, bal: bal });
      results[id] = 'added';
    });

    if (rows.length) {
      appendSorted_(master, rows);
      Object.keys(perAccount).forEach(nick => appendSorted_(ensureTxnSheet_(ss, sheetNameFor_(nick)), perAccount[nick]));
      updateBalances_(ss, moves);
      addToStats_(rows, before);
      bumpRev_();
    }
    touchDevice_(ss, device, rows.length);
    return { results: results, added: rows.length };
  } finally {
    lock.releaseLock();
  }
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
    // Insert inside the data block (after row 2) so formats, banding and colour rules extend automatically,
    // then write [new rows…, old top row] starting at row 2.
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
  const nicks = sh.getRange(2, A.NICK, last - 1, 1).getValues();
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

// ============================================================ ACCOUNTS

function readAccounts_(ss) {
  const sh = ensureAccountsSheet_(ss);
  const last = sh.getLastRow();
  if (last < 2) return [];
  return sh.getRange(2, 1, last - 1, ACC_HEADERS.length).getValues()
    .filter(r => String(r[A.NICK - 1]).trim() && String(r[A.NUM - 1]).trim())
    .map(r => ({
      nickname: String(r[A.NICK - 1]).trim(),
      bank: String(r[A.BANK - 1]).trim(),
      number: String(r[A.NUM - 1]).replace(/[^0-9]/g, ''),
      owner: /personal/i.test(String(r[A.OWNER - 1])) ? 'Personal' : 'Company',
      aliases: String(r[A.ALIASES - 1]).split(/[,\s;]+/).map(s => s.replace(/[^0-9]/g, '')).filter(s => s.length >= 3),
      active: !(r[A.PAUSED - 1] === true || /^(true|yes|paused)$/i.test(String(r[A.PAUSED - 1]).trim())),
      balance: r[A.BAL - 1] === '' ? null : Number(r[A.BAL - 1]),
      balanceAsOf: r[A.BAL_AT - 1] instanceof Date ? r[A.BAL_AT - 1].getTime() : null,
    }));
}

function publicAccount_(a) { return a; }

function addAccount_(ss, acc) {
  const nick = String(acc.nickname || '').trim();
  const num = String(acc.number || '').replace(/[^0-9]/g, '');
  if (!nick) throw new Error('Nickname is required');
  if (num.length < 4) throw new Error('Account number needs at least 4 digits');
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    if (readAccounts_(ss).some(a => a.nickname.toLowerCase() === nick.toLowerCase())) throw new Error('That nickname already exists');
    const sh = ensureAccountsSheet_(ss);
    const aliases = Array.isArray(acc.aliases) ? acc.aliases.join(', ') : String(acc.aliases || '');
    sh.appendRow([nick, String(acc.bank || ''), "'" + num, /personal/i.test(acc.owner) ? 'Personal' : 'Company', aliases, false, '', '', 'Added from app']);
    ensureTxnSheet_(ss, sheetNameFor_(nick));
    rebuildReports();
    bumpRev_();
    return { added: nick };
  } finally { lock.releaseLock(); }
}

// ============================================================ FAST READS FOR THE APP
//
// The app never scans the whole sheet:
//  • totals come from a small per-account, per-month index kept in Script Properties and updated on every push
//  • history pages read only the 25 rows they need (tabs are newest-first)
//  • replies are cached; REV changes on every write and on hand edits, so the cache never serves stale data

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
 * One page of transactions, newest first.
 * params: account (nickname, optional), type (CREDIT|DEBIT, optional), q (search, optional),
 *         offset (row offset, default 0), limit (default 25, max 100)
 * returns: items, nextOffset (pass back for the next page), hasMore, total (rows in that tab)
 */
function history_(ss, p) {
  p = p || {};
  const nick = String(p.account || '').trim();
  const type = /^(CREDIT|DEBIT)$/i.test(String(p.type || '')) ? String(p.type).toUpperCase() : '';
  const q = String(p.q || '').trim().toLowerCase().slice(0, 60);
  const limit = Math.min(Math.max(parseInt(p.limit, 10) || PAGE, 1), 100);
  const offset = Math.max(parseInt(p.offset, 10) || 0, 0);

  const sh = ss.getSheetByName(nick ? sheetNameFor_(nick) : TAB.MASTER);
  if (!sh) return { items: [], nextOffset: offset, hasMore: false, total: 0 };
  const total = Math.max(sh.getLastRow() - 1, 0);
  const rev = PropertiesService.getScriptProperties().getProperty('REV') || '0';
  const run = () => scanPage_(sh, total, type, q, offset, limit);
  return q ? run() : cached_(['h', rev, sh.getSheetId(), total, type, offset, limit].join(':'), run);
}

function scanPage_(sh, total, type, q, offset, limit) {
  const W = C.SMS; // everything up to and including the original SMS
  const filtered = !!(type || q);
  const CHUNK = filtered ? 500 : limit;
  const MAX_SCAN = 5000; // keeps one request well under a few seconds even on huge sheets
  const items = [];
  let pos = offset, scanned = 0;
  while (pos < total && items.length < limit && scanned < MAX_SCAN) {
    const data = sh.getRange(pos + 2, 1, Math.min(CHUNK, total - pos), W).getValues();
    let i = 0;
    for (; i < data.length && items.length < limit; i++) {
      const r = data[i];
      if (!String(r[C.ID - 1]).trim()) continue;
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
  // columns B (date) … K (debit)
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

/** Called inside the push lock: adds the new rows to the index, or flags a rebuild if the index is out of step. */
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
    if (name === TAB.DASH || name === TAB.MONTHLY || name === TAB.DEVICES) return;
    const obj = { REV: String(Date.now()) };
    if (name === TAB.MASTER) obj.ST_DIRTY = '1';
    PropertiesService.getScriptProperties().setProperties(obj);
  } catch (err) { /* never block an edit */ }
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
  if (sh) return sh;
  sh = ss.insertSheet(TAB.ACCOUNTS);
  sh.getRange(1, 1, 1, ACC_HEADERS.length).setValues([ACC_HEADERS])
    .setFontWeight('bold').setBackground(COLORS.header).setFontColor(COLORS.headerText).setWrap(true).setVerticalAlignment('middle');
  sh.setRowHeight(1, 42);
  sh.setFrozenRows(1);
  [170, 140, 170, 120, 170, 110, 140, 160, 220].forEach((w, i) => sh.setColumnWidth(i + 1, w));
  const rows = sh.getMaxRows() - 1;
  sh.getRange(2, A.NUM, rows, 1).setNumberFormat('@');
  sh.getRange(2, A.BAL, rows, 1).setNumberFormat(FMT);
  sh.getRange(2, A.BAL_AT, rows, 1).setNumberFormat('dd-MMM-yyyy hh:mm AM/PM');
  sh.getRange(2, A.OWNER, rows, 1).setDataValidation(SpreadsheetApp.newDataValidation().requireValueInList(['Company', 'Personal'], true).build());
  sh.getRange(2, A.PAUSED, rows, 1).insertCheckboxes();
  sh.getRange(2, A.BAL, rows, 2).setBackground('#F1F5F9').setFontColor('#475569');
  sh.getRange('A2').setNote('One row per bank account. Example:\nHDFC Current | HDFC Bank | 50200012345678 | Company | 4321 | (leave Paused unticked)\n\nOnly SMS for accounts listed here are captured. Columns G–H are filled automatically.');
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
  readAccounts_(ss).forEach(a => ensureTxnSheet_(ss, sheetNameFor_(a.nickname)));
  buildDashboard_(ss);
  buildMonthly_(ss);
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

  const head = ['Account', 'Bank', 'A/c', 'Owner', 'Current Balance',
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
      a.nickname, a.bank, '••' + a.number.slice(-4), a.owner,
      '=IFERROR(INDEX(' + ACC + '$G:$G,MATCH(' + key + ',' + ACC + '$A:$A,0)),"")',
      '=' + sumIf(cr, mth), '=' + sumIf(dr, mth), '=F' + r + '-G' + r,
      '=' + sumIf(cr, yr), '=' + sumIf(dr, yr), '=I' + r + '-J' + r,
      '=' + sumIf(cr), '=' + sumIf(dr), '=L' + r + '-M' + r,
      '=IF(COUNTIF(' + M + '$E:$E,' + key + ')=0,"",MAXIFS(' + M + '$B:$B,' + M + '$E:$E,' + key + '))',
    ]);
  });
  const n = accounts.length;
  if (n) sh.getRange(H + 1, 1, n, head.length).setValues(f);

  // Totals: Company, Personal, Grand
  const tStart = H + 1 + n + 1;
  const first = H + 1, lastR = Math.max(H + n, H + 1);
  const labels = [['Company total', '"Company"'], ['Personal total', '"Personal"'], ['GRAND TOTAL', null]];
  labels.forEach((l, i) => {
    const r = tStart + i;
    const row = [l[0], '', '', ''];
    for (let c = 5; c <= 14; c++) {
      const col = colLetter_(c);
      const rng = col + first + ':' + col + lastR;
      row.push(n ? (l[1] ? '=SUMIF($D$' + first + ':$D$' + lastR + ',' + l[1] + ',' + rng + ')' : '=SUM(' + rng + ')') : 0);
    }
    row.push('');
    sh.getRange(r, 1, 1, head.length).setValues([row]).setFontWeight('bold')
      .setBackground(i === 2 ? COLORS.accent : '#EDE9FE').setFontColor(i === 2 ? '#FFFFFF' : COLORS.header);
  });

  const all = sh.getRange(H + 1, 5, n + 4, 10);
  all.setNumberFormat(FMT_NET);
  if (n) {
    sh.getRange(H + 1, 15, n, 1).setNumberFormat('dd-MMM-yy hh:mm AM/PM');
    sh.getRange(H + 1, 1, n, head.length).applyRowBanding(SpreadsheetApp.BandingTheme.LIGHT_GREY, false, false);
    [6, 9, 12].forEach(c => sh.getRange(H + 1, c, n, 1).setFontColor(COLORS.credit));
    [7, 10, 13].forEach(c => sh.getRange(H + 1, c, n, 1).setFontColor(COLORS.debit));
    sh.getRange(H + 1, 1, n, 1).setFontWeight('bold');
  } else {
    sh.getRange(H + 1, 1).setValue('Add your bank accounts in the "Accounts" tab, then use LedgerSync ▸ Rebuild Dashboard.').setFontColor('#64748B');
  }
  sh.setColumnWidth(1, 190); sh.setColumnWidth(2, 130); sh.setColumnWidth(3, 70); sh.setColumnWidth(4, 90);
  for (let c = 5; c <= 14; c++) sh.setColumnWidth(c, 125);
  sh.setColumnWidth(15, 150);
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
  sh.getRange('H2').setFormula('=IFERROR(QUERY(' + M + ',"select C, E, H, sum(J), sum(K), sum(J)-sum(K), count(A) where C is not null group by C, E, H order by C desc, E label C \'Month\', E \'Account\', H \'Owner\', sum(J) \'Credit\', sum(K) \'Debit\', sum(J)-sum(K) \'Net\', count(A) \'Txns\'",0),"No data yet")');
  sh.getRange('P1').setValue('Year-wise by account').setFontWeight('bold').setFontSize(14).setFontColor(COLORS.header);
  sh.getRange('P2').setFormula('=IFERROR(QUERY(' + M + ',"select D, E, sum(J), sum(K), sum(J)-sum(K) where D is not null group by D, E order by D desc, E label D \'Year\', E \'Account\', sum(J) \'Credit\', sum(K) \'Debit\', sum(J)-sum(K) \'Net\'",0),"No data yet")');
  sh.getRange('B3:D').setNumberFormat(FMT_NET);
  sh.getRange('K3:M').setNumberFormat(FMT_NET);
  sh.getRange('R3:T').setNumberFormat(FMT_NET);
  ['A2:E2', 'H2:N2', 'P2:T2'].forEach(a => sh.getRange(a).setFontWeight('bold').setBackground(COLORS.header).setFontColor('#FFFFFF'));
  sh.setColumnWidth(1, 90); sh.setColumnWidth(8, 90); sh.setColumnWidth(9, 170); sh.setColumnWidth(16, 70); sh.setColumnWidth(17, 170);
  [2, 3, 4, 11, 12, 13, 18, 19, 20].forEach(c => sh.setColumnWidth(c, 125));
  sh.setColumnWidth(6, 30); sh.setColumnWidth(7, 30); sh.setColumnWidth(15, 30);
  sh.setFrozenRows(2);
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
  [TAB.DASH, TAB.MONTHLY, TAB.MASTER].forEach((name, i) => {
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

/** Rebuilds Dashboard + per-account tabs when the account list changed (e.g. hand edits in the Accounts tab). */
function syncReportsIfAccountsChanged_(ss) {
  const sig = readAccounts_(ss).map(a => a.nickname + (a.active ? '' : '-') + a.owner).join('|');
  const props = PropertiesService.getScriptProperties();
  if (props.getProperty('ACC_SIG') === sig) return;
  rebuildReports();
  props.setProperty('ACC_SIG', sig);
}
