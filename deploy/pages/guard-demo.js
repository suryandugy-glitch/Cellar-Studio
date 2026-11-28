import { checkCompiledContract, compareExportHeaders, compileContract } from './guard-engine.js';

const section = document.querySelector('#live-demo');
const controls = section.querySelector('[data-demo-controls]');
const editor = section.querySelector('#guard-demo-csv');
const fix = controls.querySelector('[data-demo-sample="fixed"]');
const verdict = section.querySelector('[data-demo-verdict]');
const next = section.querySelector('[data-demo-next]');
const results = section.querySelector('[data-demo-results]');
const retry = section.querySelector('[data-demo-retry]');
const contractText = section.querySelector('[data-demo-contract]');
const samples = ['passing', 'breaking', 'fixed'];

let contract;
let exportsByName;

function showStatus(state, heading, detail) {
  verdict.dataset.state = state;
  verdict.textContent = heading;
  next.textContent = detail;
  results.replaceChildren();
}

function renderIntegration(result) {
  const item = document.createElement('li');
  const heading = document.createElement('strong');
  const status = !result.issueCount ? 'PASS' : result.severity === 'warning' ? 'WARN' : 'BLOCK';
  heading.textContent = status + ' · ' + result.integration;
  item.append(heading);

  if (result.issueCount) {
    const list = document.createElement('ul');
    for (const issue of result.issues.slice(0, 3)) {
      const finding = document.createElement('li');
      const location = [issue.column, issue.record > 1 ? 'record ' + issue.record : ''].filter(Boolean);
      finding.textContent = (location.length ? location.join(' · ') + ' — ' : '') + issue.message;
      list.append(finding);
    }
    if (result.issueCount > list.children.length) {
      const remainder = document.createElement('li');
      remainder.textContent = 'Showing 3 of ' + result.issueCount + ' findings. Use the full checker for more detail.';
      list.append(remainder);
    }
    item.append(list);
  }

  return item;
}

function demoCsv() {
  if (editor.value.length > 65_536) throw new Error('Use the full checker for exports larger than this demo supports.');
  return editor.value;
}

function showInputError(error) {
  editor.setAttribute('aria-invalid', 'true');
  showStatus('error', 'Could not check this CSV', error.message);
}

function compareSample() {
  let changes;
  try {
    changes = compareExportHeaders(contract, exportsByName.passing, demoCsv());
  } catch (error) {
    showInputError(error);
    return;
  }

  editor.removeAttribute('aria-invalid');
  const changed = changes.added.length || changes.removed.length || changes.reordered;
  showStatus('compare', changed ? 'Header changes found' : 'Headers match',
    'Compared with the passing sample. Run check to verify your export against the import rules.');
  for (const [label, columns] of [['Added columns', changes.added], ['Removed columns', changes.removed]]) {
    const item = document.createElement('li');
    const names = columns.slice(0, 12).join(', ');
    const remaining = columns.length > 12 ? ' and ' + (columns.length - 12) + ' more' : '';
    item.textContent = label + ': ' + (names || 'None') + remaining;
    results.append(item);
  }
  const order = document.createElement('li');
  order.textContent = 'Shared column order changed: ' + (changes.reordered ? 'Yes' : 'No');
  results.append(order);
}

function runCheck() {
  fix.disabled = editor.value !== exportsByName.breaking;
  let report;
  try {
    report = checkCompiledContract(contract, demoCsv());
  } catch (error) {
    showInputError(error);
    return;
  }

  editor.removeAttribute('aria-invalid');
  const count = report.rowsChecked + (report.rowsChecked === 1 ? ' record checked. ' : ' records checked. ');
  if (report.blocking) {
    const hint = !fix.disabled ? ' Apply the sample fix to restore order_id.' : '';
    showStatus('block', 'BLOCK · Fix before releasing', count + 'A required integration failed.' + hint);
  } else if (report.passed) {
    showStatus('pass', 'PASS · Export meets the rules', count + 'Both sample integrations passed.');
  } else {
    showStatus('warn', 'WARN · Review the findings', count + 'Review warnings before releasing.');
  }

  results.append(...report.results.map(renderIntegration));
}

async function readSample(path) {
  const response = await fetch('./samples/' + path);
  if (!response.ok) throw new Error('Could not load a demo sample.');
  return response.text();
}

function runSample(name) {
  editor.value = exportsByName[name];
  runCheck();
}

async function loadDemo() {
  controls.disabled = true;
  retry.hidden = true;
  showStatus('loading', 'Loading samples…', '');
  try {
    const [source, ...csvs] = await Promise.all([
      readSample('contract.json'),
      ...samples.map(name => readSample(name + '.csv')),
    ]);
    contract = compileContract(source);
    exportsByName = Object.fromEntries(samples.map((name, index) => [name, csvs[index]]));
    contractText.textContent = source.trim();
    controls.disabled = false;
    runSample('passing');
  } catch {
    showStatus('error', 'Could not load the demo', 'Reload the samples, or open the full checker.');
    contractText.textContent = 'The sample rules could not load.';
    retry.hidden = false;
  }
}

for (const button of controls.querySelectorAll('[data-demo-sample]')) {
  button.addEventListener('click', () => runSample(button.dataset.demoSample));
}
controls.querySelector('[data-demo-run]').addEventListener('click', runCheck);
controls.querySelector('[data-demo-compare]').addEventListener('click', compareSample);
editor.addEventListener('input', () => {
  fix.disabled = editor.value !== exportsByName.breaking;
  editor.removeAttribute('aria-invalid');
  showStatus('ready', 'Sample edited', 'Select Run check to validate your changes.');
});
retry.addEventListener('click', loadDemo);
loadDemo();
