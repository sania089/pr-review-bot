/**
 * Public install link of the GitHub App, e.g. https://github.com/apps/<your-app>.
 * Set VITE_GITHUB_APP_URL at build time. Empty until the App is public (Day 10).
 */
export const INSTALL_URL: string = import.meta.env.VITE_GITHUB_APP_URL ?? ''

/** Source repository, shown in the footer. Optional. */
export const REPO_URL: string = import.meta.env.VITE_REPO_URL ?? ''
