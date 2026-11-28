import { accountRequest, readAccountSession, renderAccountAvatar } from './account-client.js';

const element = id => document.getElementById(id);
const status = element('account-status');

function message(text = '', error = false) {
  status.textContent = text;
  status.hidden = !text;
  status.dataset.state = error ? 'error' : 'info';
}

const goToAccount = () => location.replace('/account.html');

function profilePage() {
  const details = element('account-profile');
  const security = element('account-security');
  const actions = element('profile-actions');
  const name = element('profile-name');
  const email = element('profile-email');
  const avatar = element('profile-avatar');
  const website = element('profile-website');
  const lastSignIn = element('last-sign-in');
  const edit = element('profile-edit');
  const form = element('profile-form');
  const save = element('profile-save');
  const cancel = element('profile-cancel');
  const editStatus = element('profile-edit-status');
  const localLogout = element('account-logout');
  const globalLogout = element('account-logout-all');
  const fields = [
    ['organization', 'profile-organization'],
    ['jobTitle', 'profile-job-title'],
    ['website', 'profile-website'],
  ];
  let currentUser;

  function renderProfile(user) {
    currentUser = user;
    details.hidden = !user;
    security.hidden = !user;
    actions.hidden = !user;
    name.textContent = user ? user.name || 'No name provided' : '';
    email.textContent = user ? user.email || 'No email provided' : '';
    if (user) renderAccountAvatar(avatar, user);
    else avatar.replaceChildren();
    for (const [key, id] of fields) {
      const value = user?.[key] || '';
      element(id).textContent = value;
      element(id + '-row').hidden = !value;
    }
    if (user?.website) website.href = user.website;
    else website.removeAttribute('href');

    const methods = user?.signInMethods?.join(', ') || '';
    element('sign-in-methods').textContent = methods;
    element('sign-in-method-row').hidden = !methods;
    const date = user?.lastSignIn ? new Date(user.lastSignIn) : null;
    element('last-sign-in-row').hidden = !date;
    lastSignIn.dateTime = user?.lastSignIn || '';
    lastSignIn.textContent = date ? new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium', timeStyle: 'short',
    }).format(date) : '';
  }

  function setEditing(open) {
    form.hidden = !open;
    edit.setAttribute('aria-expanded', String(open));
    editStatus.hidden = true;
    if (open) {
      for (const input of form.querySelectorAll('input')) input.value = currentUser?.[input.name] || '';
      element('edit-name').focus();
    } else {
      form.reset();
    }
  }

  async function refresh() {
    renderProfile(null);
    setEditing(false);
    try {
      const { user, available } = await readAccountSession();
      if (!available) return message('Accounts are not available yet.');
      if (!user) return location.replace('/signin.html');
      renderProfile(user);
      message();
    } catch {
      message('Your session could not be checked. Reload the page to try again.', true);
    }
  }

  edit.addEventListener('click', () => {
    message();
    setEditing(form.hidden);
  });
  cancel.addEventListener('click', () => {
    setEditing(false);
    edit.focus();
  });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const payload = Object.fromEntries(new FormData(form));
    edit.disabled = save.disabled = cancel.disabled = true;
    editStatus.hidden = true;
    try {
      const { user } = await accountRequest('profile', payload);
      renderProfile(user);
      setEditing(false);
      message('Profile saved.');
      window.dispatchEvent(new Event('cellar-profile-updated'));
    } catch (error) {
      editStatus.textContent = error.message;
      editStatus.dataset.state = 'error';
      editStatus.hidden = false;
    } finally {
      edit.disabled = save.disabled = cancel.disabled = false;
      if (form.hidden) edit.focus();
    }
  });
  for (const button of [localLogout, globalLogout]) {
    button.addEventListener('click', async () => {
      localLogout.disabled = globalLogout.disabled = true;
      try {
        await accountRequest('logout', button === globalLogout ? { allDevices: true } : {});
        location.replace('/signin.html');
      } catch (error) {
        message(error.message, true);
        localLogout.disabled = globalLogout.disabled = false;
      }
    });
  }
  window.addEventListener('pageshow', event => {
    if (event.persisted) void refresh();
  });
  void refresh();
}

async function signInPage() {
  const modes = [...document.querySelectorAll('[data-account-mode]')];
  const providers = [...document.querySelectorAll('[data-provider]')];
  const fields = element('account-fields');
  const name = element('account-name');
  const email = element('account-email');
  const password = element('account-password');
  const remember = element('account-remember');
  const providerStatus = element('provider-status');
  let mode = 'signin';
  let step = 'email';
  let available = false;
  const enabledProviders = new Set();

  function setStep(next) {
    step = next;
    const details = step === 'details';
    element('email-field').hidden = details;
    element('password-field').hidden = !details;
    element('name-field').hidden = !details || mode !== 'signup';
    element('account-back').hidden = !details;
    password.disabled = !details;
    name.disabled = !details || mode !== 'signup';
    name.required = details && mode === 'signup';
    element('account-submit').firstChild.textContent = details ? (mode === 'signup' ? 'Create account ' : 'Sign in ') : 'Next ';
    element('account-hint').textContent = details
      ? (mode === 'signup' ? `Create your account for ${email.value.trim()}.` : `Enter your password for ${email.value.trim()}.`)
      : (mode === 'signup' ? 'Enter your email address to create an account.' : 'Use your Cellar Studio account.');
    element('account-title').textContent = mode === 'signup' ? 'Create your account' : 'Sign in';
    if (details) (mode === 'signup' ? name : password).focus();
  }

  function setMode(next) {
    mode = next;
    const signup = mode === 'signup';
    element('account-switch-signup').hidden = signup;
    element('account-switch-signin').hidden = !signup;
    password.autocomplete = signup ? 'new-password' : 'current-password';
    password.minLength = signup ? 12 : 0;
    password.value = '';
    setStep('email');
    message();
  }

  function busy(active) {
    fields.disabled = active || !available;
    remember.disabled = active || !available;
    for (const button of modes) button.disabled = active;
    for (const button of providers) button.disabled = active || !enabledProviders.has(button.dataset.provider);
  }

  for (const button of modes) button.addEventListener('click', () => setMode(button.dataset.accountMode));
  element('account-back').addEventListener('click', () => {
    setStep('email');
    email.focus();
  });
  const params = new URLSearchParams(location.search);
  setMode(params.get('mode') === 'signup' ? 'signup' : 'signin');

  element('auth-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (!available) return;
    if (step === 'email') {
      if (!email.reportValidity()) return;
      setStep('details');
      return;
    }
    const form = Object.fromEntries(new FormData(event.currentTarget));
    busy(true);
    message();
    try {
      const result = await accountRequest(mode, { ...form, remember: remember.checked });
      if (result.signedIn) return goToAccount();
      setMode('signin');
      message('Check your inbox for a confirmation link, then sign in with your password.');
    } catch (error) {
      message(error.message, true);
    } finally {
      busy(false);
    }
  });

  for (const button of providers) {
    button.addEventListener('click', async () => {
      busy(true);
      message();
      try {
        const { url } = await accountRequest('oauth', { provider: button.dataset.provider, remember: remember.checked });
        location.assign(url);
      } catch (error) {
        message(error.message, true);
        busy(false);
      }
    });
  }

  try {
    const result = await readAccountSession();
    if (result.user) return goToAccount();
    available = result.available;
    for (const provider of result.providers || []) {
      if (available) enabledProviders.add(provider);
    }
    const pending = providers.filter(button => !enabledProviders.has(button.dataset.provider));
    providerStatus.hidden = pending.length === 0;
    if (pending.length === providers.length) {
      providerStatus.textContent = 'Google sign-in is awaiting setup.';
    }
    element('remember-field').hidden = !available;
    element('email-account').hidden = !available;
    busy(false);
    if (!available) return;
    const notice = params.get('notice');
    if (notice === 'provider') message('Provider sign-in could not be completed. Try again.', true);
    if (notice === 'confirm') message('This confirmation link is invalid or has expired.', true);
    if (notice === 'verified') message('Email verified. Sign in to access your account.');
  } catch {
    providerStatus.textContent = 'Sign-in could not be loaded. You can still explore the site as a guest.';
  }
}

if (document.body.dataset.accountPage === 'profile') profilePage();
else signInPage();
