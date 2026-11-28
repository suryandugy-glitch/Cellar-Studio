const MAX_BYTES = 8 * 1024 * 1024;
const MAX_FINDINGS = 200;
const TYPES = new Set(['string', 'integer', 'decimal', 'boolean', 'date', 'datetime']);

function requireInput(condition, message) {
  if (!condition) throw new Error(message);
}

function object(value, keys, label) {
  requireInput(value && typeof value === 'object' && !Array.isArray(value), label + ' must be an object.');
  for (const key of Object.keys(value)) requireInput(keys.includes(key), 'Unknown property in ' + label + '.');
  return value;
}

function name(value) {
  return typeof value === 'string' && value.trim() && value.length <= 256 && !/[\x00-\x1f\x7f]/.test(value);
}

function names(values, label, minimum = 1, maximum = 1000) {
  requireInput(Array.isArray(values) && values.length >= minimum && values.length <= maximum && values.every(name),
    label + ' must contain ' + minimum + '–' + maximum + ' valid names.');
  return [...new Set(values)];
}

function optional(value, key, fallback, valid) {
  if (!Object.hasOwn(value, key)) return fallback;
  requireInput(valid(value[key]), 'Invalid value for ' + key + '.');
  return value[key];
}

class NumericBound {
  constructor(source) { this.source = source; }
}

function parseContract(source) {
  // Let the platform validate syntax, then read object keys and exact numeric bounds.
  JSON.parse(source);
  const tokens = source.matchAll(/"(?:[^"\\]|\\.)*"|[{}\[\]:,]|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null/g);
  let token = tokens.next().value?.[0];
  function take() {
    const current = token;
    token = tokens.next().value?.[0];
    return current;
  }
  function read(depth, key) {
    requireInput(depth <= 64, 'Contract JSON is nested too deeply.');
    const value = take();
    if (value === '{') {
      const entries = Object.create(null);
      while (token !== '}') {
        const property = JSON.parse(take());
        requireInput(!Object.hasOwn(entries, property), 'Contract JSON has a duplicate property.');
        take(); // colon
        entries[property] = read(depth + 1, property);
        if (token !== '}') take(); // comma
      }
      take();
      return entries;
    }
    if (value === '[') {
      const items = [];
      while (token !== ']') {
        items.push(read(depth + 1));
        if (token !== ']') take();
      }
      take();
      return items;
    }
    if ((key === 'min' || key === 'max') && /^-?\d/.test(value)) return new NumericBound(value);
    return JSON.parse(value);
  }
  return read(0);
}

export function compileContract(source) {
  requireInput(new TextEncoder().encode(source).length <= 1024 * 1024, 'Contract exceeds the 1 MiB browser limit.');
  let raw;
  try {
    raw = parseContract(source);
  } catch (error) {
    if (error instanceof SyntaxError) throw new Error('Contract must be valid JSON.');
    throw error;
  }
  object(raw, ['version', 'delimiter', 'validateRows', 'integrations'], 'contract');
  optional(raw, 'version', 2, value => value === 2);
  const delimiter = optional(raw, 'delimiter', ',', value => [',', ';', '\t'].includes(value));
  const validateRows = optional(raw, 'validateRows', false, value => typeof value === 'boolean');
  requireInput(Array.isArray(raw.integrations) && raw.integrations.length >= 1 && raw.integrations.length <= 100,
    'Provide 1–100 integrations.');
  const ids = new Set();
  const integrations = raw.integrations.map(item => {
    object(item, ['id', 'owner', 'description', 'severity', 'required', 'allowed', 'order', 'minRows', 'maxRows', 'rules', 'uniqueKeys'], 'integration');
    requireInput(name(item.id) && !ids.has(item.id), 'Use a valid, unique integration ID.');
    ids.add(item.id);
    const required = names(item.required, 'required');
    const allowed = Object.hasOwn(item, 'allowed') ? names(item.allowed, 'allowed') : null;
    const order = Object.hasOwn(item, 'order') ? names(item.order, 'order') : null;
    for (const list of [allowed, order]) {
      requireInput(!list || required.every(column => list.includes(column)), 'allowed and order must include all required columns.');
    }
    const text = value => typeof value === 'string' && value.length <= 1000;
    const owner = optional(item, 'owner', 'Unassigned', text);
    optional(item, 'description', '', text);
    const severity = optional(item, 'severity', 'error', value => ['error', 'warning'].includes(value));
    const rowCount = value => Number.isInteger(value) && value >= 0 && value <= 100_000;
    const minRows = optional(item, 'minRows', null, rowCount);
    const maxRows = optional(item, 'maxRows', null, rowCount);
    requireInput(minRows === null || maxRows === null || minRows <= maxRows, 'minRows exceeds maxRows.');

    const rules = Object.hasOwn(item, 'rules') ? object(item.rules, required, 'rules') : {};
    const columns = Object.entries(rules).map(([column, rule]) => {
      object(rule, ['type', 'notEmpty', 'unique', 'enum', 'min', 'max'], 'column rule');
      const type = optional(rule, 'type', 'string', value => TYPES.has(value));
      const notEmpty = optional(rule, 'notEmpty', false, value => typeof value === 'boolean');
      const unique = optional(rule, 'unique', false, value => typeof value === 'boolean');
      const values = Object.hasOwn(rule, 'enum') ? new Set(names(rule.enum, 'enum')) : null;
      const min = readBound(rule, 'min', type);
      const max = readBound(rule, 'max', type);
      requireInput(!min || !max || compareDecimal(min, max) <= 0, 'Rule min exceeds max.');
      return { column, type, notEmpty, unique, values, min, max };
    });

    const keyGroups = optional(item, 'uniqueKeys', [], value => Array.isArray(value) && value.length >= 1 && value.length <= 16);
    const seenKeys = new Set();
    const keys = keyGroups.map(group => {
      const key = names(group, 'Each uniqueKeys group', 2, 16);
      requireInput(key.length === group.length && key.every(column => required.includes(column)), 'A key must use distinct required columns.');
      const signature = JSON.stringify([...key].sort());
      requireInput(!seenKeys.has(signature), 'Duplicate uniqueKeys group.');
      seenKeys.add(signature);
      return key;
    });
    return { id: item.id, owner, severity, required, allowed, order, minRows, maxRows, columns, keys };
  });
  const checkRows = validateRows || integrations.some(i => i.columns.length || i.keys.length || i.minRows !== null || i.maxRows !== null);
  return { delimiter, integrations, checkRows };
}

function decimal(source) {
  const match = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(source);
  requireInput(match && source.length <= 512, 'Unsupported decimal value or bound.');
  const fraction = match[3] || '';
  const digits = (match[2] + fraction).replace(/^0+/, '');
  const power = Number(match[4] || 0) - fraction.length;
  requireInput(Number.isSafeInteger(power) && Math.abs(power) <= 1_000_000, 'Numeric bound exponent exceeds the browser limit.');
  return { sign: digits ? (match[1] ? -1 : 1) : 0, digits, magnitude: digits.length + power };
}

function readBound(rule, key, type) {
  if (!Object.hasOwn(rule, key)) return null;
  requireInput(type === 'integer' || type === 'decimal', 'min and max require a numeric type.');
  const value = rule[key];
  requireInput(value instanceof NumericBound, 'Numeric bounds require a JSON number.');
  return decimal(value.source);
}

function compareDecimal(a, b) {
  if (a.sign !== b.sign) return a.sign - b.sign;
  if (!a.sign) return 0;
  if (a.magnitude !== b.magnitude) return a.sign * Math.sign(a.magnitude - b.magnitude);
  const length = Math.max(a.digits.length, b.digits.length);
  const first = a.digits.padEnd(length, '0');
  const second = b.digits.padEnd(length, '0');
  return a.sign * (first < second ? -1 : first > second ? 1 : 0);
}

function parseCsv(text, delimiter, checkRows) {
  requireInput(new TextEncoder().encode(text).length <= MAX_BYTES, 'CSV exceeds the 8 MiB limit.');
  let cursor = text.startsWith('\uFEFF') ? 1 : 0;
  function record(number) {
    const fields = [];
    let value = '';
    let state = 'open';
    function push() {
      requireInput(fields.length < 1000, 'CSV exceeds 1,000 columns.');
      fields.push(value);
      value = '';
      state = 'open';
    }
    while (cursor < text.length) {
      const character = text[cursor++];
      if (state === 'quoted') {
        if (character !== '"') value += character;
        else if (text[cursor] === '"') {
          value += '"';
          cursor++;
        } else state = 'closed';
      } else if (character === delimiter) {
        push();
      } else if (character === '\r' || character === '\n') {
        if (character === '\r' && text[cursor] === '\n') cursor++;
        push();
        return fields;
      } else if (state === 'closed') {
        throw new Error('Unexpected character after a closing quote in record ' + number + '.');
      } else if (character === '"') {
        requireInput(!value, 'Unexpected quote in record ' + number + '.');
        state = 'quoted';
      } else {
        value += character;
      }
      requireInput(value.length <= 65_536, 'A CSV field exceeds 65,536 characters.');
    }
    requireInput(state !== 'quoted', 'Unclosed quote in record ' + number + '.');
    push();
    return fields;
  }

  requireInput(cursor < text.length, 'CSV is empty.');
  const header = record(1);
  requireInput(header.every(column => column.trim() && !/[\x00-\x1f\x7f]/.test(column)), 'CSV contains an invalid or blank header.');
  requireInput(new Set(header).size === header.length, 'CSV contains duplicate header names.');
  const rows = [];
  while (checkRows && cursor < text.length) {
    requireInput(rows.length < 100_000, 'CSV exceeds 100,000 data rows.');
    const number = rows.length + 2;
    const row = record(number);
    requireInput(row.length === header.length, 'CSV record ' + number + ' has a different column count from the header.');
    rows.push(row);
  }
  return { header, rows };
}

function validDate(year, month, day) {
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return day >= 1 && day <= (days[month - 1] || 0);
}

function validType(value, type) {
  if (type === 'string') return true;
  if (type === 'integer') return value.length <= 128 && /^-?(0|[1-9][0-9]*)$/.exec(value)?.[0] === value;
  if (type === 'decimal') return value.length <= 128 && /^-?(0|[1-9][0-9]*)(\.[0-9]+)?$/.exec(value)?.[0] === value;
  if (type === 'boolean') return value === 'true' || value === 'false';
  if (type === 'date') {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    return match?.[0] === value && validDate(...match.slice(1).map(Number));
  }
  const match = /^(\d{4}|[+-]\d{4,9})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?(?:Z|[+-](\d{2})(?::(\d{2})(?::(\d{2}))?)?)$/i.exec(value);
  if (match?.[0] !== value) return false;
  const [year, month, day, hour, minute, second, offsetHour, offsetMinute, offsetSecond] = match.slice(1).map(part => Number(part || 0));
  return validDate(year, month, day) && hour < 24 && minute < 60 && second < 60 &&
    offsetMinute < 60 && offsetSecond < 60 && offsetHour * 3600 + offsetMinute * 60 + offsetSecond <= 64_800;
}

function checkIntegration(integration, header, positions, rows) {
  const { id, owner, severity, required, allowed, order, minRows, maxRows, columns, keys } = integration;
  const issues = [];
  let issueCount = 0;
  function add(code, column, record, message) {
    issueCount++;
    if (issues.length < MAX_FINDINGS) issues.push({ code, column, record, message });
  }
  for (const column of required) {
    if (!positions.has(column)) add('MISSING_COLUMN', column, 1, 'Restore the required column or coordinate the migration.');
  }
  if (allowed) {
    for (const column of header) {
      if (!allowed.includes(column)) add('UNEXPECTED_COLUMN', column, 1, 'Remove the column or review the allowed list.');
    }
  }
  if (order && (order.length !== header.length || order.some((column, i) => column !== header[i]))) {
    add('COLUMN_ORDER', '', 1, 'Put the columns in the exact order defined in the contract.');
  }
  if (minRows !== null && rows.length < minRows) add('MIN_ROWS', '', 0, 'The export has too few records.');
  if (maxRows !== null && rows.length > maxRows) add('MAX_ROWS', '', 0, 'The export has too many records.');

  for (const rule of columns) {
    const { column, type, notEmpty, unique, values, min, max } = rule;
    if (!positions.has(column)) continue;
    const index = positions.get(column);
    const seen = new Set();
    for (const [offset, row] of rows.entries()) {
      const value = row[index];
      const record = offset + 2;
      if (value === '') {
        if (notEmpty) add('EMPTY_VALUE', column, record, 'Supply a nonempty value.');
        continue;
      }
      if (unique) {
        if (seen.has(value)) add('DUPLICATE_VALUE', column, record, 'Supply a unique value.');
        seen.add(value);
      }
      if (!validType(value, type)) {
        add('INVALID_TYPE', column, record, 'Expected ' + type + '.');
        continue;
      }
      if (values && !values.has(value)) add('ENUM_VALUE', column, record, 'Use a value from the allowed list.');
      if (min || max) {
        const number = decimal(value);
        if (min && compareDecimal(number, min) < 0) add('BELOW_MIN', column, record, 'Value is below the minimum.');
        if (max && compareDecimal(number, max) > 0) add('ABOVE_MAX', column, record, 'Value exceeds the maximum.');
      }
    }
  }

  for (const key of keys) {
    if (key.some(column => !positions.has(column))) continue;
    const indexes = key.map(column => positions.get(column));
    const label = key.join(', ');
    const seen = new Set();
    for (const [offset, row] of rows.entries()) {
      const values = indexes.map(index => row[index]);
      const record = offset + 2;
      if (values.includes('')) {
        add('EMPTY_KEY', label, record, 'Fill every field in this combined key.');
        continue;
      }
      const signature = JSON.stringify(values);
      if (seen.has(signature)) add('DUPLICATE_KEY', label, record, 'Use a unique combination of values.');
      seen.add(signature);
    }
  }

  return { integration: id, owner, severity, issueCount, issues, truncated: issueCount > issues.length };
}

export function checkExport(contractSource, csv) {
  return checkCompiledContract(compileContract(contractSource), csv);
}

export function checkCompiledContract({ delimiter, integrations, checkRows }, csv) {
  const { header, rows } = parseCsv(csv, delimiter, checkRows);
  const positions = new Map(header.map((column, index) => [column, index]));
  const results = integrations.map(integration => checkIntegration(integration, header, positions, rows));
  const blocking = results.some(result => result.issueCount && result.severity === 'error');
  const passed = results.every(result => result.issueCount === 0);
  return { passed, blocking, rowsChecked: checkRows ? rows.length : null, header, results };
}

export function compareExportHeaders({ delimiter }, baselineCsv, candidateCsv) {
  const baseline = parseCsv(baselineCsv, delimiter, false).header;
  const candidate = parseCsv(candidateCsv, delimiter, false).header;
  const previous = new Set(baseline);
  const current = new Set(candidate);
  const added = candidate.filter(column => !previous.has(column));
  const removed = baseline.filter(column => !current.has(column));
  const beforeOrder = baseline.filter(column => current.has(column));
  const afterOrder = candidate.filter(column => previous.has(column));
  return { added, removed, reordered: beforeOrder.some((column, index) => column !== afterOrder[index]) };
}

export function draftHeaderContract(csv, delimiter) {
  requireInput([',', ';', '\t'].includes(delimiter), 'Choose a valid CSV delimiter.');
  const { header } = parseCsv(csv, delimiter, true);
  const draft = {
    version: 2,
    delimiter,
    validateRows: true,
    integrations: [{ id: 'customer-import', required: header }],
  };
  const source = JSON.stringify(draft, null, 2);
  compileContract(source);
  return source + '\n';
}
