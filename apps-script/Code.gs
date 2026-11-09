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

const TZ = () => Session.getScriptTimeZone() || 'Asia/Kolkata';
const COLORS = { header: '#1E1B4B', headerText: '#FFFFFF', credit: '#0F9D58', debit: '#D93025', band: '#F5F3FF', accent: '#6D4BFF' };

// ============================================================ MENU / SETUP

function onOpen() {
  SpreadsheetApp.getUi().createMenu('LedgerSync')
    .addItem('Connection details (for the app)', 'showConnection')
    .addItem('Rebuild Dashboard & Monthly', 'rebuildReports')
    .addItem('Re-sort all sheets (newest first)', 'resortAll')
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
      case 'ping': return { sheet: ss.getName(), accounts: readAccounts_(ss).length, time: new Date().toISOString() };
      case 'config': syncReportsIfAccountsChanged_(ss); return { accounts: readAccounts_(ss).map(publicAccount_) };
      case 'summary': return summary_(ss);
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
    const existing = new Set(readIds_(master));
    const accounts = readAccounts_(ss);
    const byNick = {};
    accounts.forEach(a => byNick[a.nickname.toLowerCase()] = a);

    const now = new Date();
    const rows = [];
    const perAccount = {};
    const latestBal = {};

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
      if (bal !== '' && (!latestBal[nick] || dt > latestBal[nick].dt)) latestBal[nick] = { dt: dt, bal: bal };
      results[id] = 'added';
    });

    if (rows.length) {
      appendSorted_(master, rows);
      Object.keys(perAccount).forEach(nick => appendSorted_(ensureTxnSheet_(ss, sheetNameFor_(nick)), perAccount[nick]));
      updateBalances_(ss, latestBal);
    }
    touchDevice_(ss, device, rows.length);
    return { results: results, added: rows.length };
  } finally {
    lock.releaseLock();
  }
}

function appendSorted_(sh, rows) {
  const start = Math.max(sh.getLastRow(), 1) + 1;
  sh.getRange(start, 1, rows.length, TXN_HEADERS.length).setValues(rows);
  const last = sh.getLastRow();
  if (last > 2) sh.getRange(2, 1, last - 1, TXN_HEADERS.length).sort({ column: C.DT, ascending: false });
}

function readIds_(sh) {
  const last = sh.getLastRow();
  if (last < 2) return [];
  return sh.getRange(2, C.ID, last - 1, 1).getValues().map(r => String(r[0]));
}

function updateBalances_(ss, latest) {
  const sh = ss.getSheetByName(TAB.ACCOUNTS);
  const last = sh.getLastRow();
  if (last < 2) return;
  const data = sh.getRange(2, 1, last - 1, ACC_HEADERS.length).getValues();
  data.forEach((r, i) => {
    const l = latest[String(r[A.NICK - 1])];
    if (!l) return;
    const prevAt = r[A.BAL_AT - 1] instanceof Date ? r[A.BAL_AT - 1] : null;
    if (!prevAt || l.dt >= prevAt) sh.getRange(i + 2, A.BAL, 1, 2).setValues([[l.bal, l.dt]]);
  });
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
    return { added: nick };
  } finally { lock.releaseLock(); }
}

// ============================================================ SUMMARY (for the app dashboard)

function summary_(ss) {
  const accounts = readAccounts_(ss).filter(a => a.active);
  const master = ensureTxnSheet_(ss, TAB.MASTER);
  const last = master.getLastRow();
  const data = last > 1 ? master.getRange(2, 1, last - 1, C.DR).getValues() : [];
  const now = new Date();
  const mStart = new Date(now.getFullYear(), now.getMonth(), 1);
  const yStart = new Date(now.getFullYear(), 0, 1);
  const blank = () => ({ credit: 0, debit: 0, count: 0 });
  const stats = {};
  accounts.forEach(a => stats[a.nickname] = { month: blank(), year: blank(), all: blank() });

  data.forEach(r => {
    const s = stats[String(r[C.ACC - 1])];
    if (!s) return;
    const dt = r[C.DT - 1] instanceof Date ? r[C.DT - 1] : new Date(r[C.DT - 1]);
    const cr = Number(r[C.CR - 1]) || 0, dr = Number(r[C.DR - 1]) || 0;
    const add = b => { b.credit += cr; b.debit += dr; b.count++; };
    add(s.all);
    if (dt >= yStart) add(s.year);
    if (dt >= mStart) add(s.month);
  });

  return {
    accounts: accounts.map(a => ({
      nickname: a.nickname, bank: a.bank, owner: a.owner, masked: '••' + a.number.slice(-4),
      balance: a.balance, balanceAsOf: a.balanceAsOf,
      month: round_(stats[a.nickname].month), year: round_(stats[a.nickname].year), all: round_(stats[a.nickname].all),
    })),
  };
}

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
