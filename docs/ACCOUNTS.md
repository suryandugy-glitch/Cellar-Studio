# Enable Cellar Studio accounts

Cellar Studio uses Supabase Auth to store accounts and manage passwords. The Vercel account endpoint uses PKCE for Google, verifies the session on the server, and stores it in Secure, HttpOnly cookies. The account page currently shows a name and email address. Cellar Guard files and rules use the existing browser or Java storage.

## Supabase

1. Create or choose a Supabase project. Enable the Email provider and turn on Confirm email. Use a minimum password length of 12. Configure a custom SMTP sender for public signup.
2. Under Authentication → URL Configuration, set the site URL to `https://cellar-csv.vercel.app`. Add these redirect URLs:

   ```text
   https://cellar-csv.vercel.app/api/account?action=callback
   https://cellar-csv.vercel.app/api/account?action=confirm
   ```

3. In Authentication → Email Templates → Confirm signup, point the confirmation link to the site verification endpoint:

   ```html
   <a href="{{ .SiteURL }}/api/account?action=confirm&amp;token_hash={{ .TokenHash }}&amp;type=email">Confirm your email</a>
   ```

   Opening the link verifies the email address. The user then signs in on Cellar Studio.

## Google

- In Google Cloud, create an OAuth **Web application** credential. Add the Supabase callback shown on the Google provider page, typically `https://PROJECT_REF.supabase.co/auth/v1/callback`, to **Authorized redirect URIs**. Configure the audience for the intended users and paste the client ID and secret into Supabase Authentication → Sign In / Providers → Google.

[Google setup](https://supabase.com/docs/guides/auth/social-sign-in/auth-google) · [Production email delivery](https://supabase.com/docs/guides/auth/auth-smtp)

## Vercel

| Variable | Production value |
| --- | --- |
| `CELLAR_SUPABASE_URL` | Supabase project HTTPS origin |
| `CELLAR_SUPABASE_PUBLISHABLE_KEY` | Supabase `sb_publishable_…` key |
| `CELLAR_AUTH_ORIGIN` | `https://cellar-csv.vercel.app` |
| `CELLAR_AUTH_GOOGLE` | `1` once the Google provider is set up |

Keep the Google client secret in Supabase. Deploy after setting the variables. Open `/signin.html` and check signup, email verification, Google, the account page, and sign-out. Test **Keep me signed in** by closing and reopening the browser. A valid session automatically opens the account page when a signed-in user returns to `/signin.html`.

The public `/` route always shows the Cellar Studio company homepage. Sign-in is available only at `/signin.html`, and the public products, checker and help pages remain accessible without an account.
