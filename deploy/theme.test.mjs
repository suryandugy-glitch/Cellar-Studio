import assert from 'node:assert/strict';
import { access, readFile, readdir } from 'node:fs/promises';
import { resolve } from 'node:path';

const root = resolve('build/vercel');
const signInLanding = false;
const redirects = JSON.parse(await readFile(resolve('vercel.json'), 'utf8')).redirects;
assert.ok(!redirects.some(route => route.source === '/' &&
  (!route.has || route.has.some(check => check.type === 'host' && check.value === 'cellar-csv.vercel.app'))),
  'CSV domain root must serve the Studio index without a redirect');
assert.ok(redirects.some(route => route.source === '/' && route.destination === 'https://cellar-csv.vercel.app/' &&
  route.has?.some(check => check.type === 'host' && check.value === 'cellar-studio-hq.vercel.app')),
  'legacy studio domain must lead to the CSV domain');
const files = (await readdir(root)).filter(file => file.endsWith('.html'));
assert.ok(files.length >= 23, 'expected Guard, theme editor, manual, sample, and company pages');
for (const file of files) {
  const html = await readFile(resolve(root, file), 'utf8');
  const accountPage = html.includes('data-account-page=');
  const signInPage = html.includes('data-account-page="signin"');
  assert.equal(html.split('account-menu.js').length - 1, signInPage ? 0 : 1, `${file}: account menu script`);
  assert.equal(html.split('account-menu.css').length - 1, signInPage ? 0 : 1, `${file}: account menu CSS`);
  assert.equal(html.split('theme-system.css').length - 1, 1, `${file}: missing/duplicate theme CSS`);
  assert.equal(html.split('studio-maximal.css').length - 1, 1, `${file}: missing/duplicate Studio style`);
  assert.equal(html.split('studio-footer.css').length - 1, 1, `${file}: missing/duplicate footer CSS`);
  assert.equal(html.split('cookie-consent.css').length - 1, accountPage ? 0 : 1, `${file}: consent CSS`);
  assert.equal(html.split('theme-system.js').length - 1, 1, `${file}: missing/duplicate theme JS`);
  assert.equal(html.split('cookie-consent.js').length - 1, accountPage ? 0 : 1, `${file}: consent JS`);
  for (const image of html.matchAll(/<img\b[^>]*>/gi)) {
    assert.match(image[0], /\balt\s*=/i, `${file}: image missing alt attribute`);
  }
  for (const match of html.matchAll(/(?:href|src)="\.\/([^"#?]+)(?:[?#][^"]*)?"/g)) {
    const target = match[1];
    await access(resolve(root, target)).catch(() => {
      throw new Error(`${file}: broken local asset or navigation target ${target}`);
    });
  }
}
const index = await readFile(resolve(root, 'index.html'), 'utf8');
assert.match(index, /name="google-site-verification"/, 'Search Console verification must be on the public root');
const studio = await readFile(resolve(root, 'studio.html'), 'utf8');
const signIn = await readFile(resolve(root, 'signin.html'), 'utf8');
assert.doesNotMatch(index, /data-account-page="signin"/, 'Company home must not masquerade as a login gate');
assert.match(signIn, /data-account-page="signin"/);
assert.match(signIn, /Use your Cellar Studio account/);
assert.doesNotMatch(signIn, /Microsoft account|data-provider="microsoft"/);
const home = signInLanding ? studio : index;
if (signInLanding) {
  assert.match(index, /data-account-page="signin"/);
  assert.match(index, /href="\.\/studio\.html"[^>]*>Continue as a guest/);
}
assert.match(home, /We build what/);
assert.match(home, /comes next/);
assert.match(home, /studio-vision\.css/);
assert.match(home, /studio-vision\.js/);
assert.match(home, /id="possibilities"/);
assert.match(home, /Cellar Guard/);
assert.match(home, /href="\.\/products\.html"/);
assert.match(home, /More products.*coming soon/i);
assert.match(home, /href="\.\/about\.html"/);
assert.match(home, /href="\.\/contribute\.html"/);
const canonical = signInLanding ? 'https://cellar-csv.vercel.app/studio.html' : 'https://cellar-csv.vercel.app/';
assert.ok(home.includes(`rel="canonical" href="${canonical}"`));
const guard = await readFile(resolve(root, 'guard.html'), 'utf8');
assert.match(guard, /href="\.\/products\.html"/);
const studioHref = signInLanding ? './studio.html' : './';
assert.ok(guard.includes(`href="${studioHref}">Cellar Studio</a>`));
assert.match(guard, /releases\/tag\/guard-v3\.1\.0/);
const products = await readFile(resolve(root,'products.html'),'utf8');
assert.match(products, /id="guard"/);
assert.match(products, /id="coming-soon"/);
assert.match(products, /href="\.\/guard\.html"/);
assert.match(products, /More products coming soon/);
assert.doesNotMatch(products, /SpaceFix/);
const themeEditor = await readFile(resolve(root, 'themes.html'), 'utf8');
assert.match(themeEditor, /id="theme-form"/);
assert.match(themeEditor, /id="theme-preview"/);
assert.match(themeEditor, /id="theme-library"/);
assert.match(themeEditor, /theme-studio\.js/);
assert.match(themeEditor, /Save &amp; apply to all pages/);
assert.match(themeEditor, /data-preset-filter="minimal"/);
assert.match(themeEditor, /data-preset-filter="brutalist"/);
assert.match(themeEditor, /id="palette-target"/);
assert.match(themeEditor, /name="theme-style" value="brutalist"/);
assert.ok(studio.includes(`rel="canonical" href="${canonical}"`));
assert.match(studio, /href="\.\/guard\.html"/);
assert.match(studio, /id="possibilities"/);
for (const file of files) {
  const html = await readFile(resolve(root, file), 'utf8');
  assert.doesNotMatch(html, /cellar-studio-hq\.vercel\.app/, `${file}: old public hostname`);
  const wrongAnchor = signInLanding ? './#possibilities' : './studio.html#possibilities';
  assert.ok(!html.includes(`href="${wrongAnchor}"`), `${file}: incorrect Studio section link`);
}
for (const page of ['about.html', 'licenses.html', 'certifications.html', 'privacy.html', 'terms.html', 'policies.html', 'cookie-policy.html', 'refund-policy.html', 'business.html', 'contact.html']) {
  assert.match(home, new RegExp(`href="\\./${page}"`), `home: missing ${page}`);
  const html = await readFile(resolve(root, page), 'utf8');
  assert.match(html, /company-pages\.css/);
  assert.match(html, /company-pages\.js/);
  assert.match(html, /studio-site-footer/);
  assert.doesNotMatch(html, /STUDIO_FOOTER/);
  assert.match(html, /href="\.\/products\.html"/, `${page}: product navigation`);
}
for (const page of ['about.html', 'licenses.html', 'certifications.html']) {
  const html = await readFile(resolve(root, page), 'utf8');
  assert.match(html, /aria-current="page"/);
}
for (const file of [signInLanding ? 'studio.html' : 'index.html', 'products.html', 'manual.html', 'handbook.html']) {
  const html = await readFile(resolve(root, file), 'utf8');
  assert.equal(html.split('studio-site-footer').length - 1, 1, `${file}: expected one shared footer`);
  assert.match(html, /aria-label="Footer sitemap"/);
}
const about = await readFile(resolve(root, 'about.html'), 'utf8');
const contribute = await readFile(resolve(root, 'contribute.html'), 'utf8');
assert.match(contribute, /href="https:\/\/github\.com\/suryandugy-glitch\/Cellar-Studio\/issues"/);
assert.match(contribute, /java Build\.java test/);
assert.match(contribute, /aria-current="page">Contribute/);
assert.match(contribute, /Engineering/);
assert.match(contribute, /Documentation/);
assert.match(contribute, /Security|security policy/i);
assert.match(about, /Suryandu Ganguly/);
assert.match(about, /Gunjan Roy/);
assert.match(about, /Disha Chakraborty/);
assert.match(about, /Debangana Debnath/);
assert.match(about, /Founder &amp; CTO/);
assert.match(about, /Operation Coordinator/);
assert.match(about, /behind the scenes/);
assert.match(about, /first focused product is Cellar Guard/);
const licenses = await readFile(resolve(root, 'licenses.html'), 'utf8');
assert.match(licenses, /Copyright \(c\) 2026 Cellar contributors/);
assert.match(licenses, /THE SOFTWARE IS PROVIDED "AS IS"/);
const credentials = await readFile(resolve(root, 'certifications.html'), 'utf8');
assert.match(credentials, /No verified company certifications currently listed/);
assert.doesNotMatch(credentials, /ISO 27001 certified|SOC 2 certified/i);
const privacy = await readFile(resolve(root, 'privacy.html'), 'utf8');
assert.match(privacy, /cellar-privacy-preferences/);
assert.match(privacy, /submits the form to FormSubmit/);
const cookies = await readFile(resolve(root, 'cookie-policy.html'), 'utf8');
assert.match(cookies, /data-open-privacy-settings/);
assert.match(cookies, /Google Fonts and Vercel hosting/);
const refund = await readFile(resolve(root, 'refund-policy.html'), 'utf8');
assert.match(refund, /does not operate a paid checkout/);
const business = await readFile(resolve(root, 'business.html'), 'utf8');
assert.match(business, /Not publicly verified or announced/);
const contact = await readFile(resolve(root, 'contact.html'), 'utf8');
assert.match(contact, /id="enquiry-consent"[^>]*required/);
assert.match(contact, /id="enquiry-status" role="status"/);
assert.match(contact, /action="https:\/\/formsubmit\.co\/cellarcsv@gmail\.com" method="post"/);
assert.match(contact, /type="submit">Send message/);
assert.match(contact, /Clear form/);
const formScript = await readFile(resolve(root, 'enquiry-form.js'), 'utf8');
assert.doesNotMatch(formScript, /\bfetch\s*\(|XMLHttpRequest|sendBeacon|\.submit\s*\(/);
const consentScript = await readFile(resolve(root, 'cookie-consent.js'), 'utf8');
assert.match(consentScript, /cellar-privacy-preferences/);
assert.match(consentScript, /data-privacy-essential/);
assert.match(consentScript, /data-privacy-allow/);
assert.match(consentScript, /if \(!preferences\?\.embeds\)/);
assert.doesNotMatch(consentScript, /gtag\s*\(|facebook\.com\/tr|google-analytics/);
for (const retired of ['explore.html','services.html','downloads.html','product-guard.html','product-deadlock.html','product-buildclinic.html','product-clausecraft.html','product-plancliff.html','product-crashcheck.html','product-learn.html','deadlock.html','deadlock-downloads.html']) {
  assert.ok(!files.includes(retired), `${retired}: retired catalogue or unrelated product page must not be published`);
}
const manual = await readFile(resolve(root,'manual.html'),'utf8');
assert.match(manual, /id="pr"/);
assert.match(manual, /id="demo"/);
assert.match(manual, /id="web-app"/);
assert.match(manual, /id="fix"/);
assert.match(manual, /csv-compatibility/);
assert.match(manual, /Cellar Guard/);
assert.match(manual, /samples\/my-repair\.csv/);
assert.match(manual, /Download Cellar Guard ZIP/);
const js = await readFile(resolve(root, 'theme-system.js'), 'utf8');
assert.match(js, /localStorage\.setItem/);
assert.match(js, /studio-theme-menu/);
assert.match(js, /aria-haspopup="true"/);
assert.match(js, /studio-theme-current/);
assert.doesNotMatch(js, /studio-theme-caption/);
assert.match(js, /prefers-color-scheme/);
assert.match(js, /storage/);
assert.match(js, /cellar-studio-custom-theme/);
assert.match(js, /themes\.html/);
assert.match(js, /data-theme-style/);
const themeCSS = await readFile(resolve(root, 'theme-system.css'), 'utf8');
assert.match(themeCSS, /\.studio-theme-toggle\s*\{[^}]*width:42px;height:42px/s);
const studioCSS = await readFile(resolve(root, 'studio-maximal.css'), 'utf8');
assert.match(studioCSS, /img\[src="\.\/logo\.svg"\]/);
assert.match(studioCSS, /@keyframes cellar-logo-breathe/);
assert.match(studioCSS, /@keyframes cellar-logo-greet/);
assert.match(studioCSS, /prefers-reduced-motion:reduce/);
const themeStudioJS = await readFile(resolve(root, 'theme-studio.js'), 'utf8');
assert.match(themeStudioJS, /readLibrary/);
assert.match(themeStudioJS, /saveCustom/);
assert.match(themeStudioJS, /contrast/);
assert.match(themeStudioJS, /Bauhaus Punch/);
assert.match(themeStudioJS, /colorPalettes/);
console.log(`${files.length} Guard-only HTML pages pass theme and navigation checks.`);
