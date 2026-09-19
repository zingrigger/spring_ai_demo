export default {
  brand: { name: 'Auth Server', slogan: 'Secure sign-in,\nstarts here.' },
  login: {
    title: 'Sign in',
    subtitle: 'Continue with your organization account',
    account: 'Account',
    password: 'Password',
    submit: 'Sign in',
    submitting: 'Signing in…',
    invalid: 'Invalid account or password.',
    failed: 'Sign-in failed. Please try again.',
  },
  organizations: {
    title: 'Choose an organization',
    subtitle: 'You belong to several organizations. Pick one to continue.',
    submit: 'Continue',
    denied: 'This account has no organization. Please contact an administrator.',
    failed: 'Could not load organizations. Please refresh.',
  },
  consent: {
    title: 'Authorize access',
    subtitle: '{client} requests access to your account',
    asOrganization: 'Authorizing as {organization}',
    approve: 'Approve',
    failed: 'Could not load the authorization request. Please restart the flow from your client.',
  },
  home: {
    title: 'Signed in',
    subtitle: 'There is no pending authorization request',
    account: 'Account',
    organization: 'Organization',
    logout: 'Sign out',
  },
  scope: {
    profile: 'Read your basic profile',
    weatherRead: 'Read weather data',
  },
}
