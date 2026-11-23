import type { BaseLayoutProps } from "fumadocs-ui/layouts/shared";
import { appName, gitConfig } from "./shared";
import { AudioLines } from "lucide-react";

export function baseOptions(): BaseLayoutProps {
  return {
    nav: {
      title: (
        <>
          <AudioLines className="size-5 text-fd-primary" aria-hidden="true" />
          <span>{appName}</span>
          <span className="text-xs font-normal text-fd-muted-foreground">
            Docs
          </span>
        </>
      ),
    },
    githubUrl: `https://github.com/${gitConfig.user}/${gitConfig.repo}`,
    links: [
      { text: "Guide", url: "/docs", active: "nested-url" },
      { text: "Downloads", url: "/docs/downloads" },
      { text: "Release status", url: "/docs/release-candidate" },
    ],
  };
}
