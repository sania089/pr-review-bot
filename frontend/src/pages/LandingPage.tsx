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

/** A real review the bot posted on this project's own pull request. */
function ExampleReview() {
  return (
    <figure className="mx-auto w-full max-w-2xl">
      <img
        src="/demo-review.png"
        width={1175}
        height={460}
        alt="A pull request review comment from the bot on DiffTruncator.java: a high severity finding that removing the line which increments 'used' stops the character limit from being enforced."
        className="w-full rounded-lg border border-border"
      />
      <figcaption className="mt-2 text-center text-xs text-muted-foreground">
        A real review comment the bot posted on this project&apos;s own pull request.
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
