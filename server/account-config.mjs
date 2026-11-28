export function accountConfig(env = process.env) {
  const url = env.CELLAR_SUPABASE_URL?.trim();
  const key = env.CELLAR_SUPABASE_PUBLISHABLE_KEY?.trim();
  if (!url || !key) return null;

  const service = new URL(url);
  const origin = new URL(env.CELLAR_AUTH_ORIGIN || 'https://cellar-csv.vercel.app');
  const validOrigin = value => value.protocol === 'https:' && value.href === value.origin + '/';
  if (!validOrigin(service) || !validOrigin(origin) || !key.startsWith('sb_publishable_')) {
    throw new Error('Use HTTPS origins and a Supabase publishable key for Cellar accounts.');
  }

  const providers = {};
  if (env.CELLAR_AUTH_GOOGLE === '1') providers.google = 'google';
  return { url: service.origin, origin: origin.origin, key, providers };
}
