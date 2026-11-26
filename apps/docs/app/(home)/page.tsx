import Image from "next/image";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { releaseUrl } from "@/lib/shared";

const guides = [
  [
    "Import a recording",
    "Open a local file, listen to the source and inspect a region.",
    "/docs/android/offline#install-and-use-the-preview",
  ],
  [
    "Enhance a revision",
    "Apply effects to a separate render and revisit earlier edits.",
    "/docs/android/enhancement-workbench",
  ],
  [
    "Review a transcript",
    "Compare segments with the audio and save examiner notes.",
    "/docs/android/offline#examiner-notes",
  ],
  [
    "Transfer a case",
    "Export a protected case and verify it on another device.",
    "/docs/evidence/protected-transfer",
  ],
];

export default function HomePage() {
  return (
    <main className="docs-home">
      <section className="docs-hero">
        <div>
          <p className="docs-kicker">Android workbench · 0.6 test candidate</p>
          <h1>From recording to reviewed transcript.</h1>
          <p className="docs-lead">
            Inspect a waveform, enhance a working copy and review speech against
            the original. Follow a synthetic recording through your first case
            in EchoScript.
          </p>
          <div className="docs-actions">
            <Link
              className="docs-primary"
              href="/docs/android/offline#install-and-use-the-preview"
            >
              Install and start <ArrowRight size={17} aria-hidden="true" />
            </Link>
            <Link href="/docs">Browse guides</Link>
          </div>
          <p className="docs-status">
            Android 10+ · Development preview · BETA GATE: NOT PASSED
          </p>
          <a className="docs-text-link" href={releaseUrl}>
            Release files and checksums
          </a>
        </div>
        <figure className="workbench-preview">
          <Image
            src="/images/workbench-timeline.png"
            width={320}
            height={640}
            alt="EchoScript Analyze screen with a selected interval above the waveform and spectrogram."
            priority
            unoptimized
          />
          <figcaption>Analyze · Synthetic speech sample</figcaption>
        </figure>
      </section>
      <section className="docs-tasks">
        <h2>Workbench guides</h2>
        <div>
          {guides.map(([title, summary, href]) => (
            <Link href={href} key={href}>
              <h3>
                {title}
                <ArrowRight size={16} aria-hidden="true" />
              </h3>
              <p>{summary}</p>
            </Link>
          ))}
        </div>
      </section>
    </main>
  );
}
