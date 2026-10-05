/**
 * Google Apps Script endpoint for the Expense Notification Forwarder.
 *
 * Configure SCRIPT_TOKEN, SPREADSHEET_ID, and SHEET_NAME in Script Properties.
 * Never put the token in a spreadsheet cell or log.
 */
function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonResponse_({ ok: false, code: "missing_body" });
    }

    var payload;
    try {
      payload = JSON.parse(e.postData.contents);
    } catch (error) {
      return jsonResponse_({ ok: false, code: "invalid_json" });
    }
    if (!payload || typeof payload !== "object") {
      return jsonResponse_({ ok: false, code: "invalid_payload" });
    }

    var properties = PropertiesService.getScriptProperties();
    var expectedToken = properties.getProperty("SCRIPT_TOKEN");
    if (!expectedToken ||
        expectedToken.length < 32 ||
        expectedToken.length > 256 ||
        !constantTimeEquals_(String(payload.token || ""), expectedToken)) {
      return jsonResponse_({ ok: false, code: "unauthorized" });
    }

    if (payload.action === "ping") {
      var pingSpreadsheetId = properties.getProperty("SPREADSHEET_ID");
      var pingSheetName = properties.getProperty("SHEET_NAME");
      if (!pingSpreadsheetId || !pingSheetName) {
        return jsonResponse_({ ok: false, code: "configuration_missing" });
      }
      var pingSpreadsheet;
      try {
        pingSpreadsheet = SpreadsheetApp.openById(pingSpreadsheetId);
      } catch (error) {
        return jsonResponse_({ ok: false, code: "spreadsheet_unavailable" });
      }
      if (!pingSpreadsheet.getSheetByName(pingSheetName)) {
        return jsonResponse_({ ok: false, code: "sheet_not_found" });
      }
      return jsonResponse_({ ok: true, action: "ping" });
    }

    var validation = validatePayload_(payload);
    if (validation) {
      return jsonResponse_({ ok: false, code: validation });
    }

    var spreadsheetId = properties.getProperty("SPREADSHEET_ID");
    var sheetName = properties.getProperty("SHEET_NAME");
    if (!spreadsheetId || !sheetName) {
      return jsonResponse_({ ok: false, code: "configuration_missing" });
    }

    if (!lock.tryLock(10000)) {
      return jsonResponse_({ ok: false, code: "busy" });
    }

    var spreadsheet;
    try {
      spreadsheet = SpreadsheetApp.openById(spreadsheetId);
    } catch (error) {
      return jsonResponse_({ ok: false, code: "spreadsheet_unavailable" });
    }
    var sheet = spreadsheet.getSheetByName(sheetName);
    if (!sheet) {
      return jsonResponse_({ ok: false, code: "sheet_not_found" });
    }
    if (sheet.getLastRow() === 0) {
      sheet.appendRow(["Timestamp", "Merchant", "Amount", "Source", "Raw"]);
    }

    sheet.appendRow([
      String(payload.timestamp),
      safeCellText_(payload.merchant),
      Number(payload.amount),
      safeCellText_(payload.source),
      safeCellText_(payload.raw || ""),
    ]);
    return jsonResponse_({ ok: true });
  } catch (error) {
    return jsonResponse_({ ok: false, code: "temporary_error" });
  } finally {
    if (lock.hasLock()) {
      lock.releaseLock();
    }
  }
}

function validatePayload_(payload) {
  if (!payload || typeof payload !== "object") return "invalid_payload";
  if (typeof payload.timestamp !== "string" ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(payload.timestamp) ||
      !Number.isFinite(Date.parse(payload.timestamp))) return "invalid_timestamp";
  if (typeof payload.merchant !== "string" ||
      payload.merchant.trim().length === 0 ||
      payload.merchant.length > 120) return "invalid_merchant";
  if (!Number.isSafeInteger(payload.amount) ||
      payload.amount <= 0 ||
      payload.amount > 1000000000000) return "invalid_amount";
  if (typeof payload.source !== "string" ||
      payload.source.trim().length === 0 ||
      payload.source.length > 100) return "invalid_source";
  if (payload.raw !== undefined &&
      (typeof payload.raw !== "string" || payload.raw.length > 3500)) {
    return "invalid_raw";
  }
  return null;
}

function safeCellText_(value) {
  var text = String(value);
  return /^[\s]*[=+\-@]/.test(text) ? "'" + text : text;
}

function constantTimeEquals_(provided, expected) {
  var length = Math.max(provided.length, expected.length);
  var difference = provided.length ^ expected.length;
  for (var i = 0; i < length; i++) {
    difference |= (provided.charCodeAt(i) || 0) ^ (expected.charCodeAt(i) || 0);
  }
  return difference === 0;
}

function jsonResponse_(value) {
  return ContentService
    .createTextOutput(JSON.stringify(value))
    .setMimeType(ContentService.MimeType.JSON);
}
