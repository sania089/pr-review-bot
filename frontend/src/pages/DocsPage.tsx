import { useEffect, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { InstallButton } from '@/components/InstallButton'
import { usePageTitle } from '@/lib/usePageTitle'

const CONFIG_EXAMPLE = `# Files to leave out of the review, on top of lockfiles, generated and vendored files.
ignore:
  - docs/          # a directory: everything below it
  - "*.md"         # no slash: matches at any depth
  - /build         # leading slash: only at the repo root
  - src/**/gen/**  # ** crosses directories, * stays inside one

# low:    only high severity findings, at most 5 comments
# normal: medium and high, at most 10 comments (default)
# high:   everything, at most 15 comments
strictness: normal`

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-h`} className="scroll-mt-6 space-y-3">
      <h2 id={`${id}-h`} className="text-2xl font-semibold">
        {title}
      </h2>
      {children}
    </section>
  )
}

export function DocsPage() {
  usePageTitle('Docs')
  const { hash } = useLocation()

  // The router does not scroll to #anchors on its own.
  useEffect(() => {
    if (hash) document.getElementById(hash.slice(1))?.scrollIntoView?.()
  }, [hash])

  return (
    <main className="mx-auto max-w-3xl space-y-10 px-4 py-10 leading-7">
      <h1 className="text-3xl font-bold">Documentation</h1>

      <Section id="setup" title="Setup">
        <ol className="list-decimal space-y-2 pl-6">
          <li>
            Install the GitHub App on your account or organization and choose the repositories.
            <div className="mt-2">
              <InstallButton />
            </div>
          </li>
          <li>Open a pull request (not a draft). The bot reviews it when it is opened or updated.</li>
          <li>Read the review: a summary plus inline comments, each with a severity.</li>
        </ol>
        <p className="text-muted-foreground">
          Each commit is reviewed once. Pushing a new commit triggers a new review; a redelivered webhook never posts
          twice.
        </p>
      </Section>

      <Section id="scope" title="What gets reviewed">
        <p>The bot looks for bugs, security problems and correctness issues. It does not comment on style.</p>
        <p>These are skipped automatically:</p>
        <ul className="list-disc pl-6">
          <li>Draft pull requests and pull requests opened by bots such as Dependabot.</li>
          <li>Lockfiles, minified and generated files, binaries and vendored directories.</li>
          <li>Deleted files, pure renames and mode changes.</li>
          <li>Very large diffs: files that do not fit are listed in the summary as not reviewed.</li>
        </ul>
      </Section>

      <Section id="config" title="Per-repository settings: .prbot.yml">
        <p>
          Put a <code>.prbot.yml</code> file in the root of the repository. It is read from the pull request&apos;s{' '}
          <strong>base branch</strong>, so a pull request cannot change its own review rules.
        </p>
        <pre className="overflow-x-auto rounded-lg border border-border bg-muted p-4 text-sm">
          <code>{CONFIG_EXAMPLE}</code>
        </pre>
        <p className="text-muted-foreground">
          Unknown keys, a wrong type or invalid YAML make the bot ignore the whole file and use the defaults. The review
          summary says so.
        </p>
      </Section>

      <Section id="data" title="Data handling">
        <p>
          The changed lines of each pull request are sent to the configured LLM provider to produce the review. Review
          metadata (repository, PR number, commit, status, token counts) is stored in a database. Comment text and source
          code are not stored.
        </p>
        <p>
          The default deployment uses free LLM tiers. Some free tiers may use prompts to improve the provider&apos;s
          products, so install the bot on <strong>public repositories only</strong>.
        </p>
      </Section>
    </main>
  )
}
