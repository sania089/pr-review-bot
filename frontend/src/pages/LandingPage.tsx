import { Link } from 'react-router-dom'
import { InstallButton } from '@/components/InstallButton'
import { Card } from '@/components/ui/card'
import { usePageTitle } from '@/lib/usePageTitle'

const STEPS = [
  {
    title: 'Install the GitHub App',
    body: 'Pick the repositories you want reviewed. It needs read access to code and write access to pull requests.',
  },
  {
    title: 'Open a pull request',
    body: 'The bot starts when a PR is opened or updated. Drafts and bot-authored PRs are skipped.',
  },
  {
    title: 'Get a review',
    body: 'Within about a minute you get a summary and inline comments on the lines that matter.',
  },
]

const FEATURES = [
  {
    title: 'Bugs, security, correctness',
    body: 'No style nits. Every finding has a severity so you can tune the noise.',
  },
  {
    title: 'Comments only on changed lines',
    body: 'Comments that do not map to the diff are dropped instead of posted in the wrong place.',
  },
  {
    title: 'Tunable per repository',
    body: 'A .prbot.yml file sets ignored paths and how strict the review is.',
  },
]

/** Illustrative example only; replace with a real screenshot once the bot has reviewed a public PR. */
function ExampleReview() {
  return (
    <figure className="mx-auto w-full max-w-2xl" aria-label="Example review comment">
      <Card className="overflow-hidden p-0">
        <div className="border-b border-border bg-muted px-4 py-2 font-mono text-xs text-muted-foreground">
          src/main/java/app/UserRepository.java
        </div>
        <pre className="overflow-x-auto px-4 py-3 text-xs leading-6">
          <code>
            <span className="text-danger">- </span>
            {'String sql = "SELECT * FROM users WHERE name = ?";\n'}
            <span className="text-primary">+ </span>
            {'String sql = "SELECT * FROM users WHERE name = \'" + name + "\'";'}
          </code>
        </pre>
        <div className="border-t border-border p-4 text-sm">
          <p className="mb-1 font-semibold">
            <span className="mr-2 rounded-full bg-red-100 px-2 py-0.5 text-xs text-red-800 dark:bg-red-950 dark:text-red-300">
              high
            </span>
            SQL injection
          </p>
          <p className="text-muted-foreground">
            <code>name</code> is concatenated into the query, so a crafted value returns every user. Use a prepared
            statement with a bound parameter.
          </p>
        </div>
      </Card>
      <figcaption className="mt-2 text-center text-xs text-muted-foreground">
        Example of an inline review comment.
      </figcaption>
    </figure>
  )
}

export function LandingPage() {
  usePageTitle()
  return (
    <main>
      <section className="mx-auto max-w-5xl px-4 py-12 text-center sm:py-20">
        <h1 className="text-3xl font-bold tracking-tight sm:text-5xl">Code review on every pull request</h1>
        <p className="mx-auto mt-4 max-w-2xl text-lg text-muted-foreground">
          An open-source GitHub App that reads your diff and comments on real bugs, security holes and correctness
          problems, right on the lines that changed.
        </p>
        <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
          <InstallButton />
          <Link to="/docs" className="rounded-md border border-border px-5 py-2.5 text-sm font-medium hover:bg-muted">
            Read the docs
          </Link>
        </div>
      </section>

      <section className="mx-auto max-w-5xl px-4 pb-12">
        <ExampleReview />
      </section>

      <section aria-labelledby="how" className="mx-auto max-w-5xl px-4 py-12">
        <h2 id="how" className="mb-6 text-center text-2xl font-semibold">
          How it works
        </h2>
        <ol className="grid gap-4 md:grid-cols-3">
          {STEPS.map((s, i) => (
            <li key={s.title}>
              <Card className="h-full">
                <div className="mb-2 flex h-8 w-8 items-center justify-center rounded-full bg-primary text-sm font-semibold text-primary-foreground">
                  {i + 1}
                </div>
                <h3 className="font-semibold">{s.title}</h3>
                <p className="mt-1 text-sm text-muted-foreground">{s.body}</p>
              </Card>
            </li>
          ))}
        </ol>
      </section>

      <section aria-labelledby="why" className="mx-auto max-w-5xl px-4 py-12">
        <h2 id="why" className="mb-6 text-center text-2xl font-semibold">
          What you get
        </h2>
        <ul className="grid gap-4 md:grid-cols-3">
          {FEATURES.map((f) => (
            <li key={f.title}>
              <Card className="h-full">
                <h3 className="font-semibold">{f.title}</h3>
                <p className="mt-1 text-sm text-muted-foreground">{f.body}</p>
              </Card>
            </li>
          ))}
        </ul>
        <p className="mt-8 text-center text-sm text-muted-foreground">
          Runs on free tiers, so it is best for public repositories.{' '}
          <Link to="/docs#data" className="underline">
            What happens to your code
          </Link>
        </p>
      </section>
    </main>
  )
}
