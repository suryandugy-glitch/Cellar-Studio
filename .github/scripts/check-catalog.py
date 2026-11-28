"""Browser smoke checks for Studio HQ and its only product, Cellar Guard."""
import json
import os
import re
from pathlib import Path

from urllib.parse import parse_qs

from playwright.sync_api import sync_playwright


BASE = 'http://127.0.0.1:8186/'
COMPANY_PAGES = [
    'about', 'licenses', 'certifications', 'privacy', 'terms', 'policies',
    'cookie-policy', 'refund-policy', 'business', 'contact',
]
ROUTES = ['index', 'products', 'guard', 'manual', 'guide', 'walkthrough', 'handbook', 'guard-app', 'themes'] + COMPANY_PAGES


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(
        executable_path=os.environ['CHROME_BIN'], args=['--no-sandbox']
    )
    page = browser.new_page()
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))

    # Every published route should be readable without horizontal scrolling.
    for width in (320, 390, 768, 1440):
        page.set_viewport_size({'width': width, 'height': 900})
        for route in ROUTES:
            response = page.goto(BASE + route + '.html')
            assert response.status == 200, route
            assert page.locator('h1').count() == 1, route
            page.get_by_role('button', name='Ask Cellar', exact=True).wait_for()
            overflow = page.evaluate('document.documentElement.scrollWidth - innerWidth')
            assert overflow <= 1, (width, route, overflow)

    # The product demo runs the checker against sample and edited CSV content.
    page.set_viewport_size({'width': 390, 'height': 780})
    page.goto(BASE + 'products.html')
    page.get_by_role('link', name='Try live demo').click()
    assert page.url == BASE + 'guard.html#live-demo'
    demo = page.get_by_role('region', name='Try Cellar Guard live')
    verdict = demo.get_by_role('status')
    demo.locator('[data-state="pass"]').wait_for()
    reload_samples = demo.get_by_role('button', name='Reload samples')
    assert reload_samples.is_hidden()
    fix = demo.get_by_role('button', name='Apply sample fix')
    assert fix.is_disabled()
    demo.get_by_role('button', name='Run breaking sample').click()
    assert 'BLOCK' in verdict.inner_text()
    assert 'order_id' in demo.locator('[data-demo-results]').inner_text()
    assert 'PASS · finance-summary' in demo.locator('[data-demo-results]').inner_text()
    demo.get_by_role('button', name='Compare to passing sample').click()
    assert 'Header changes found' in verdict.inner_text()
    comparison = demo.locator('[data-demo-results]').inner_text()
    assert 'Added columns: id' in comparison
    assert 'Removed columns: order_id' in comparison
    assert fix.is_enabled()
    fix.click()
    assert 'PASS' in verdict.inner_text()
    assert 'order_id,id,' in demo.get_by_label('Sample CSV', exact=True).input_value()
    assert fix.is_disabled()

    editor = demo.get_by_label('Sample CSV', exact=True)
    editor.fill('order_id,sku,total_cents,currency\nA-1,SEED,-1,INR\n')
    assert 'Sample edited' in verdict.inner_text()
    assert demo.locator('[data-demo-results]').inner_text() == ''
    demo.get_by_role('button', name='Run check').click()
    assert 'BLOCK' in verdict.inner_text()
    assert 'total_cents' in demo.locator('[data-demo-results]').inner_text()
    editor.fill('order_id,sku,total_cents,currency\nA-1,only-two-columns\n')
    demo.get_by_role('button', name='Run check').click()
    assert 'Could not check this CSV' in verdict.inner_text()
    assert editor.get_attribute('aria-invalid') == 'true'
    demo.get_by_role('button', name='Run passing sample').click()
    assert 'PASS' in verdict.inner_text()
    assert editor.get_attribute('aria-invalid') is None
    assert demo.get_by_role('link', name='Open checker for your own CSV').get_attribute('href') == './guard-app.html'
    assert page.evaluate('document.documentElement.scrollWidth - innerWidth') <= 1

    # A real no-data choice, persisted and reversible in the same browser.
    page.set_viewport_size({'width': 1440, 'height': 900})
    page.goto(BASE + 'cookie-policy.html')
    page.get_by_role('button', name='Essential only').click()
    prefs = page.evaluate("JSON.parse(localStorage.getItem('cellar-privacy-preferences'))")
    assert prefs['embeds'] is False, prefs

    page.get_by_role('button', name='Manage privacy settings').click()
    dialog = page.get_by_role('dialog')
    assert dialog.is_visible()
    dialog.get_by_role('checkbox').check()
    dialog.get_by_role('button', name='Save preferences').click()
    assert page.evaluate("JSON.parse(localStorage.getItem('cellar-privacy-preferences')).embeds") is True
    page.get_by_role('button', name='Open privacy settings').click()
    dialog.get_by_role('checkbox').uncheck()
    dialog.get_by_role('button', name='Save preferences').click()
    assert page.evaluate("JSON.parse(localStorage.getItem('cellar-privacy-preferences')).embeds") is False

    # Use the production policy and intercept the external POST so CI never sends email.
    headers = json.loads(Path('vercel.json').read_text())['headers']
    policy = next(header['value'] for rule in headers for header in rule['headers']
                  if header['key'] == 'Content-Security-Policy')

    def serve_contact_with_policy(route):
        response = route.fetch()
        route.fulfill(response=response, headers={
            **response.headers, 'content-security-policy': policy,
        })

    page.route(BASE + 'contact.html', serve_contact_with_policy)
    endpoint = 'https://formsubmit.co/cellarcsv@gmail.com'
    submissions = []

    def receive_contact_post(route):
        request = route.request
        submissions.append((request.method, parse_qs(request.post_data or '')))
        route.fulfill(status=200, content_type='text/html', body='<h1>Message received</h1>')

    page.route(endpoint, receive_contact_post)
    page.goto(BASE + 'index.html')
    page.get_by_role('navigation').get_by_role('link', name='Contact us').filter(visible=True).first.click()
    assert page.url == BASE + 'contact.html'
    send = page.get_by_role('button', name='Send message')
    send.click()
    assert page.locator('#enquiry-consent').get_attribute('aria-invalid') == 'true'
    assert page.locator('#enquiry-topic').evaluate('element => element === document.activeElement')
    assert not submissions

    page.locator('#enquiry-email').fill('invalid-address')
    page.locator('#enquiry-topic').select_option(label='Collaboration idea')
    message = 'A sample collaboration enquiry.\nTests: CSV & Java? Café.'
    page.locator('#enquiry-message').fill(message)
    page.locator('#enquiry-consent').press('Space')
    assert page.locator('#enquiry-consent').is_checked()
    send.click()
    assert page.locator('#enquiry-email').get_attribute('aria-invalid') == 'true'
    assert not submissions

    page.get_by_role('button', name='Clear form').click()
    assert page.locator('#enquiry-message').input_value() == ''
    assert not page.locator('#enquiry-consent').is_checked()
    subjects = set()
    for attempt in range(2):
        if attempt:
            page.goto(BASE + 'contact.html')
        page.locator('#enquiry-name').fill('Sample Visitor')
        page.locator('#enquiry-email').fill('visitor+test@example.com')
        page.locator('#enquiry-topic').select_option(label='Collaboration idea')
        page.locator('#enquiry-message').fill(message)
        page.locator('#enquiry-consent').check()
        with page.expect_navigation(url=endpoint):
            send.click()
        assert page.get_by_role('heading', name='Message received').is_visible()
        assert len(submissions) == attempt + 1
        method, fields = submissions[attempt]
        assert method == 'POST'
        subject, = fields.pop('_subject')
        assert re.fullmatch(r'Cellar Studio \| Collaboration idea \| #[0-9A-F]{16}', subject)
        subjects.add(subject)
        assert fields == {
            '_url': ['https://cellar-csv.vercel.app/contact.html'],
            'name': ['Sample Visitor'],
            'email': ['visitor+test@example.com'],
            'topic': ['Collaboration idea'],
            'message': [message.replace('\n', '\r\n')],
        }
    assert len(subjects) == 2, 'Identical enquiries must still use different email subjects.'

    # Small-screen navigation has a keyboard-reachable open/close control.
    page.set_viewport_size({'width': 390, 'height': 780})
    page.goto(BASE + 'about.html')
    menu = page.get_by_role('button', name='Open navigation')
    assert menu.is_visible()
    menu.click()
    assert page.get_by_role('button', name='Close navigation').get_attribute('aria-expanded') == 'true'
    page.keyboard.press('Escape')
    assert page.get_by_role('button', name='Open navigation').get_attribute('aria-expanded') == 'false'

    # A single appearance preference must work on phones and follow visitors
    # from the Studio homepage to Guard and its manual.
    page.set_viewport_size({'width': 320, 'height': 760})
    page.emulate_media(color_scheme='light')
    page.goto(BASE + 'index.html')
    appearance = page.locator('#studio-theme-toggle')
    assert appearance.is_visible()
    appearance.click()
    assert page.locator('#studio-theme-menu').is_visible()
    assert page.evaluate('document.documentElement.scrollWidth - innerWidth') <= 1
    page.locator('input[name="studio-theme-choice"][value="dark"]').check()
    assert page.locator('html').get_attribute('data-theme') == 'dark'
    assert page.evaluate("localStorage.getItem('cellar-studio-theme')") == 'dark'
    page.goto(BASE + 'guard.html')
    assert page.locator('html').get_attribute('data-theme') == 'dark'
    page.goto(BASE + 'manual.html')
    appearance = page.locator('#studio-theme-toggle')
    appearance.click()
    page.locator('input[name="studio-theme-choice"][value="system"]').check()
    assert page.evaluate("localStorage.getItem('cellar-studio-theme')") == 'system'
    page.emulate_media(color_scheme='dark')
    page.wait_for_function("document.documentElement.dataset.theme === 'dark'")
    assert page.locator('html').get_attribute('data-theme') == 'dark'
    page.emulate_media(color_scheme='light')
    page.wait_for_function("document.documentElement.dataset.theme === 'light'")
    assert page.locator('html').get_attribute('data-theme') == 'light'
    appearance.click()
    page.keyboard.press('Escape')
    assert appearance.get_attribute('aria-expanded') == 'false'
    appearance.click()
    page.locator('input[name="studio-theme-choice"][value="light"]').check()

    # Visitors can create and apply a named custom palette on a separate page.
    page.set_viewport_size({'width': 390, 'height': 850})
    page.goto(BASE + 'themes.html')
    page.get_by_role('button', name='Minimalism', exact=True).click()
    assert page.locator('#theme-presets .theme-preset').count() == 4
    page.get_by_role('button', name='Load Paper & Ink palette').click()
    assert page.locator('#theme-preview').get_attribute('data-style') == 'minimal'
    assert page.locator('#theme-preview').evaluate('el => getComputedStyle(el).borderTopWidth') == '1px'
    page.get_by_role('button', name='Brutalism', exact=True).click()
    assert page.locator('#theme-presets .theme-preset').count() == 4
    page.get_by_role('button', name='Load Bauhaus Punch palette').click()
    assert page.locator('#theme-preview').get_attribute('data-style') == 'brutalist'
    assert page.locator('#theme-preview').evaluate('el => getComputedStyle(el).borderTopWidth') == '4px'
    page.locator('#palette-target').select_option('accent')
    page.get_by_role('button', name='Ocean #087e8b color').click()
    assert page.locator('#theme-accent').input_value() == '#087e8b'
    page.locator('#theme-name').fill('My ocean palette')
    page.locator('input[name="theme-style"][value="minimal"]').check()
    assert page.locator('#theme-preview').get_attribute('data-style') == 'minimal'
    assert '#087E8B' in page.locator('label[for="theme-accent"]').inner_text()
    page.locator('#theme-corners').select_option('rounded')
    page.locator('#theme-type').select_option('large')
    page.locator('#theme-motion').select_option('reduced')
    page.get_by_role('button', name='Save & apply to all pages').click()
    assert page.locator('html').get_attribute('data-theme-preference') == 'custom'
    assert page.locator('html').get_attribute('data-theme-corners') == 'rounded'
    assert page.locator('html').get_attribute('data-theme-type') == 'large'
    assert page.locator('html').get_attribute('data-theme-style') == 'minimal'
    assert page.locator('#theme-library article').count() == 1
    saved = page.evaluate("JSON.parse(localStorage.getItem('cellar-studio-custom-theme'))")
    assert saved['name'] == 'My ocean palette' and saved['accent'] == '#087e8b'
    assert saved['style'] == 'minimal'
    page.goto(BASE + 'guard-app.html')
    assert page.locator('html').get_attribute('data-theme-preference') == 'custom'
    assert page.evaluate("getComputedStyle(document.body).getPropertyValue('--guard-violet').trim()") == '#087e8b'
    assert page.locator('.guard-card').first.evaluate('el => getComputedStyle(el).borderTopWidth') == '1px'
    page.goto(BASE + 'themes.html')
    page.get_by_role('button', name='Use', exact=True).click()
    assert page.locator('html').get_attribute('data-theme-preference') == 'custom'
    page.locator('#studio-theme-toggle').click()
    page.locator('input[name="studio-theme-choice"][value="light"]').check()
    assert page.locator('html').get_attribute('data-theme-preference') == 'light'

    # The homepage must promote one product and link to its public manual.
    page.goto(BASE + 'index.html')
    assert page.locator('.hero-copy').get_by_role('link', name='Explore our products').count() == 1
    assert page.locator('.hero-copy').get_by_role('link', name='Meet the team').count() == 1
    page.goto(BASE + 'products.html')
    assert page.locator('#coming-soon').count() == 1
    page.goto(BASE + 'manual.html')
    assert page.locator('#pr').count() == 1

    # Exercise actual JSON and CSV file selection using the production worker policy.
    page.route(BASE + 'guard-app.html', serve_contact_with_policy)
    for width in (390, 1440):
        page.set_viewport_size({'width': width, 'height': 900})
        page.goto(BASE + 'guard-app.html')
        page.get_by_text('Import or edit JSON', exact=True).click()
        contract = page.locator('#contract-file')
        contract.set_input_files({
            'name': 'invalid.json', 'mimeType': 'application/json', 'buffer': b'{"integrations": []}',
        })
        page.get_by_text('Contract error. Provide 1–100 integrations.', exact=True).wait_for()
        assert contract.get_attribute('aria-invalid') == 'true'

        example = Path('cellar.json').read_bytes()
        contract.set_input_files({
            'name': 'Cellar-Guard-Example-Contract.json', 'mimeType': 'application/json', 'buffer': example,
        })
        page.get_by_text('Contract valid. Choose a CSV or load an example to run a check.', exact=True).wait_for()
        assert contract.get_attribute('aria-invalid') == 'false'
        assert json.loads(page.locator('#contract-text').input_value()) == json.loads(example)

        page.get_by_role('button', name='Load breaking example').click()
        page.get_by_role('heading', name='Release blocked').wait_for()
        assert page.get_by_text('MISSING_COLUMN', exact=True).is_visible()
        assert page.get_by_text('order_id · record 1', exact=True).is_visible()
        assert 'Cellar-Guard-Example-Contract.json' in page.locator('#contract-file-status').inner_text()

        page.locator('#csv-file').set_input_files({
            'name': 'valid.csv', 'mimeType': 'text/csv',
            'buffer': b'order_id,sku,total_cents,currency\nPRIVATE-TEST-ORDER,SKU1,100,INR\n',
        })
        page.get_by_role('heading', name='Ready to ship').wait_for()
        with page.expect_download() as saved:
            page.get_by_role('button', name='Download report').click()
        report_text = saved.value.path().read_text()
        assert json.loads(report_text)['passed'] is True
        assert 'PRIVATE-TEST-ORDER' not in report_text

        with page.expect_download() as saved:
            page.get_by_role('button', name='Download contract', exact=True).click()
        assert json.loads(saved.value.path().read_text()) == json.loads(example)
        page.get_by_text('Create a starter contract from a known-good CSV', exact=True).click()
        with page.expect_download() as saved:
            page.get_by_role('button', name='Download starter JSON').click()
        starter = json.loads(saved.value.path().read_text())
        assert starter['integrations'][0]['required'] == ['order_id', 'sku', 'total_cents', 'currency']
        assert 'PRIVATE-TEST-ORDER' not in json.dumps(starter)

        page.get_by_role('button', name='Clear inputs').click()
        assert page.locator('#contract-text').input_value() == ''
        assert page.locator('#csv-text').input_value() == ''
        assert page.get_by_role('button', name='Download report').is_disabled()
        page.locator('#csv-file').set_input_files({
            'name': 'first.csv', 'mimeType': 'text/csv',
            'buffer': b'order_id,sku,total_cents,currency\nO-1,SKU1,100,INR\n',
        })
        page.get_by_text('CSV selected. Use your import rules or choose a JSON contract to run the check.', exact=True).wait_for()
        contract.set_input_files({'name': 'contract.json', 'mimeType': 'application/json', 'buffer': example})
        page.get_by_role('heading', name='Ready to ship').wait_for()

        # Complete a check entirely through the built-in rules builder.
        page.goto(BASE + 'guard-app.html')
        apply = page.get_by_role('button', name='Use these rules')
        apply.click()
        page.get_by_text('Enter at least one required column.', exact=True).wait_for()
        page.get_by_label('Required columns', exact=True).fill('order_id\nsku')
        page.get_by_label('Contract delimiter').select_option(';')
        apply.click()
        page.get_by_text('Contract valid. Choose a CSV or load an example to run a check.', exact=True).wait_for()
        page.get_by_label('CSV text', exact=True).fill('id;sku\n1;sample\n')
        page.get_by_role('button', name='Check export').click()
        page.get_by_role('heading', name='Release blocked').wait_for()
        assert page.get_by_text('order_id · record 1', exact=True).is_visible()

        page.get_by_label('CSV text', exact=True).fill('order_id;sku\n1;sample\n')
        page.get_by_text('Create a starter contract from a known-good CSV', exact=True).click()
        page.get_by_label('CSV delimiter', exact=True).select_option(';')
        page.get_by_role('button', name='Fill required columns').click()
        page.get_by_text('Review the required columns and select Use these rules to apply them.', exact=True).wait_for()
        assert page.get_by_label('Required columns', exact=True).input_value() == 'order_id\nsku'
        apply.click()
        page.get_by_role('heading', name='Ready to ship').wait_for()

        remember = page.get_by_role('checkbox', name='Remember these rules on this device')
        remember.check()
        apply.click()
        page.get_by_text('Rules saved on this device.', exact=True).wait_for()
        assert page.evaluate("JSON.parse(localStorage.getItem('cellar-guard-saved-rules'))") == {
            'columns': 'order_id\nsku', 'delimiter': ';',
        }

        page.reload()
        page.get_by_text('Saved rules loaded. Tap Use these rules to apply them.', exact=True).wait_for()
        assert page.get_by_label('Required columns', exact=True).input_value() == 'order_id\nsku'
        assert page.get_by_label('Contract delimiter').input_value() == ';'
        assert page.get_by_label('CSV text', exact=True).input_value() == ''

        page.get_by_role('link', name='Add CSV', exact=True).click()
        assert page.url.endswith('#csv-title')
        page.get_by_role('link', name='Get help', exact=True).click()
        assert page.url.endswith('#guard-help')
        page.get_by_text('Can I check a CSV without a JSON file?', exact=True).click()
        assert page.get_by_text('Yes. Enter one required column name per line', exact=False).is_visible()
        with page.expect_popup() as support:
            page.get_by_role('link', name='Ask for help', exact=False).click()
        contact_page = support.value
        contact_page.wait_for_load_state()
        assert contact_page.url == BASE + 'contact.html?topic=guard#enquiry-form'
        assert contact_page.locator('#enquiry-topic').input_value() == 'Cellar Guard support'
        contact_page.close()
        assert page.get_by_label('Required columns', exact=True).input_value() == 'order_id\nsku'

        page.get_by_role('button', name='Forget saved rules').click()
        assert page.evaluate("localStorage.getItem('cellar-guard-saved-rules')") is None
        page.reload()
        assert page.get_by_label('Required columns', exact=True).input_value() == ''

        assert page.locator('#studio-theme-toggle').is_visible()
        assert page.locator('html').get_attribute('data-theme') == 'light'
        assert page.evaluate('document.documentElement.scrollWidth - innerWidth') <= 1

    # Exercise the assistant without consuming model credits.
    for width in (390, 1440):
        page.set_viewport_size({'width': width, 'height': 850})
        page.goto(BASE + 'guard-app.html')
        page.route(BASE + 'api/site-assistant', lambda route: route.fulfill(status=503))
        page.get_by_role('button', name='Ask Cellar', exact=True).click()
        assistant = page.get_by_role('dialog', name='Ask Cellar')
        assert assistant.is_visible()
        assistant.get_by_role('button', name='How do I contact support?').click()
        assistant.get_by_text('Site guide', exact=True).wait_for()
        assert assistant.locator('a[href$="contact.html"]').first.is_visible()
        assert page.evaluate('document.documentElement.scrollWidth - innerWidth') <= 1
        assistant.get_by_role('button', name='Close', exact=True).click()
        assert not assistant.is_visible()
        assert page.get_by_role('button', name='Ask Cellar', exact=True).evaluate(
            'element => element === document.activeElement')
        page.unroute(BASE + 'api/site-assistant')

    # General chat carries follow-up context and New chat clears it.
    requests = []

    def mock_assistant(route):
        requests.append(route.request.post_data_json)
        route.fulfill(json={'mode': 'ai', 'answer': 'Worked example reply.', 'sources': []})

    page.set_viewport_size({'width': 320, 'height': 650})
    page.route(BASE + 'api/site-assistant', mock_assistant)
    page.goto(BASE + 'guard-app.html?ask=1')
    assistant = page.get_by_role('dialog', name='Ask Cellar')
    assistant.get_by_role('button', name='Expand').click()
    assert assistant.get_by_role('button', name='Expand').get_attribute('aria-pressed') == 'true'
    layout = assistant.evaluate('''element => ({
        fits: element.scrollWidth <= element.clientWidth + 1,
        overflow: [element, ...element.querySelectorAll('*')]
            .filter(node => node.scrollWidth > node.clientWidth + 1)
            .map(node => [node.className || node.tagName, node.clientWidth, node.scrollWidth]),
    })''')
    assert layout['fits'], layout['overflow']
    assistant.get_by_role('button', name='Explain a general topic').click()
    assistant.get_by_text('Worked example reply.', exact=True).wait_for()
    assistant.get_by_label('Your question').fill('What happens in year three?')
    assistant.get_by_role('button', name='Ask', exact=True).click()
    assistant.get_by_text('Worked example reply.', exact=True).nth(1).wait_for()
    assert requests[1]['history'] == [
        {'role': 'user', 'content': requests[0]['question']},
        {'role': 'assistant', 'content': 'Worked example reply.'},
    ]
    assistant.get_by_role('button', name='New chat').click()
    assert assistant.get_by_text('Worked example reply.', exact=True).count() == 0
    assistant.get_by_role('button', name='Show me a CSV demo').click()
    assistant.get_by_text('Worked example reply.', exact=True).wait_for()
    assert requests[2]['history'] == []

    assert not errors, errors
    print(f'Checked {len(ROUTES)} routes at four widths, privacy choices, enquiry validation and mobile menu.')
    browser.close()
