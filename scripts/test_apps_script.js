const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('apps_script/Code.gs', 'utf8');

function createHarness(overrides = {}) {
  const rows = [];
  const sheet = {
    getLastRow: () => rows.length,
    appendRow: (row) => rows.push(row),
  };
  const properties = {
    SCRIPT_TOKEN: 'test-secret-with-at-least-32-characters',
    SPREADSHEET_ID: 'sheet-id',
    SHEET_NAME: 'Transactions',
    ...overrides,
  };
  const context = {
    PropertiesService: {
      getScriptProperties: () => ({
        getProperty: (key) => properties[key] ?? null,
      }),
    },
    SpreadsheetApp: {
      openById: (id) => {
        if (id !== properties.SPREADSHEET_ID) throw new Error('not found');
        return {
          getSheetByName: (name) => name === properties.SHEET_NAME ? sheet : null,
        };
      },
    },
    LockService: {
      getScriptLock: () => ({
        tryLock: () => true,
        hasLock: () => true,
        releaseLock: () => {},
      }),
    },
    ContentService: {
      MimeType: { JSON: 'application/json' },
      createTextOutput: (content) => ({
        content,
        setMimeType() { return this; },
      }),
    },
  };
  vm.createContext(context);
  vm.runInContext(source, context);
  return {
    doPost: (payload) => context.doPost({
      postData: { contents: JSON.stringify(payload) },
    }),
    rows,
    secret: properties.SCRIPT_TOKEN,
  };
}

function response(output) {
  return JSON.parse(output.content);
}

{
  const app = createHarness();
  const result = response(app.doPost({ token: 'incorrect' }));
  assert.equal(result.code, 'unauthorized');
  assert.equal(app.rows.length, 0);
}

{
  const app = createHarness();
  const result = response(app.doPost({ token: app.secret, action: 'ping' }));
  assert.equal(result.ok, true);
  assert.equal(app.rows.length, 0);
}

{
  const app = createHarness();
  const result = response(app.doPost({
    token: app.secret,
    timestamp: '2026-10-05T16:30:00+07:00',
    merchant: '=IMPORTXML("bad")',
    amount: 125000,
    source: 'Bank',
    raw: '',
  }));
  assert.equal(result.ok, true);
  assert.deepEqual(
    Array.from(app.rows[0]),
    ['Timestamp', 'Merchant', 'Amount', 'Source', 'Raw'],
  );
  assert.equal(app.rows[1][1], "'=IMPORTXML(\"bad\")");
  assert.equal(app.rows[1][2], 125000);
}

{
  const app = createHarness();
  const result = response(app.doPost({
    token: app.secret,
    timestamp: '2026-10-05T16:30:00+07:00',
    merchant: 'Merchant',
    amount: '125000',
    source: 'Bank',
    raw: '',
  }));
  assert.equal(result.code, 'invalid_amount');
  assert.equal(app.rows.length, 0);
}

console.log('Apps Script contract tests passed.');
