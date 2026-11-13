import Link from "next/link";
import {
  ArrowRight,
  AudioLines,
  FileAudio,
  History,
  ShieldCheck,
} from "lucide-react";
import { apkUrl, releaseUrl } from "@/lib/shared";

const guides = [
  {
    title: "Import and listen",
    text: "Choose a local recording, confirm the attachment and explore the timeline.",
    href: "/docs/android/offline",
    icon: FileAudio,
  },
  {
    title: "Shape a working render",
    text: "Learn presets, signed controls, selected regions and revision history.",
    href: "/docs/android/enhancement-workbench",
    icon: AudioLines,
  },
  {
    title: "Preserve and verify",
    text: "Understand protected case transfer, checkpoints and their trust boundaries.",
    href: "/docs/evidence/protected-transfer",
    icon: ShieldCheck,
  },
  {
    title: "Know the release status",
    text: "See the checks that passed and the work still required for acceptance.",
    href: "/docs/release-candidate",
    icon: History,
  },
];

export default function HomePage() {
  return (
    <main className="mx-auto w-full max-w-6xl px-6 py-14 md:py-24">
      <div className="max-w-3xl">
        <p className="mb-6 inline-flex items-center gap-2 rounded-full border border-fd-border bg-fd-card px-3 py-1.5 text-sm text-fd-primary">
          <AudioLines className="size-4" aria-hidden="true" /> EchoScript
          documentation · 0.6 Test
        </p>
        <h1 className="text-4xl font-semibold leading-tight tracking-tight md:text-6xl">
          A clearer guide to your audio workflow.
        </h1>
        <p className="mt-6 max-w-2xl text-lg leading-relaxed text-fd-muted-foreground">
          Import local audio, explore the spectrum, shape a separate render and
          review the transcript. Keep the original and understand what each
          revision changes.
        </p>
        <div className="mt-8 flex flex-wrap gap-3">
          <Link
            href="/docs"
            className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-fd-primary px-5 py-3 font-medium text-fd-primary-foreground"
          >
            Read the guide <ArrowRight className="size-4" aria-hidden="true" />
          </Link>
          <a
            href={apkUrl}
            className="inline-flex min-h-11 items-center rounded-xl border border-fd-border bg-fd-card px-5 py-3 font-medium"
          >
            Download 0.6 Test APK
          </a>
        </div>
        <p className="mt-4 text-sm text-fd-muted-foreground">
          Android 10+ · Synthetic evaluation ·{" "}
          <a href={releaseUrl} className="underline underline-offset-4">
            Release notes and checksums
          </a>
        </p>
      </div>
      <div className="mt-14 grid gap-4 sm:grid-cols-2">
        {guides.map(({ title, text, href, icon: Icon }) => (
          <Link
            key={href}
            href={href}
            className="group rounded-2xl border border-fd-border bg-fd-card p-6 transition-colors hover:bg-fd-accent focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-fd-ring"
          >
            <Icon className="mb-5 size-6 text-fd-primary" aria-hidden="true" />
            <h2 className="text-lg font-semibold">{title}</h2>
            <p className="mt-2 leading-relaxed text-fd-muted-foreground">
              {text}
            </p>
          </Link>
        ))}
      </div>
      <aside className="mt-8 rounded-2xl border border-fd-border px-6 py-5 text-sm leading-relaxed text-fd-muted-foreground">
        <p>
          <strong className="text-fd-foreground">
            Development preview. BETA GATE: NOT PASSED.
          </strong>{" "}
          Emulator results do not replace physical-device acceptance. Preserve
          verified case exports before changing installed previews.
        </p>
        <p className="mt-2">All implemented workbench tools are available. External provider charges and technical resource limits still apply.</p>
      </aside>
    </main>
  );
}
