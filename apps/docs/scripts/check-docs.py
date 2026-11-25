"""Check the rendered reader navigation from the landing page, including fragments."""
import argparse
from collections import deque
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urljoin, urlsplit

class Page(HTMLParser):
    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.ids, self.links = set(), []
        self.feed(html)

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if values.get("id"):
            self.ids.add(values["id"])
        if tag == "a":
            if values.get("name"):
                self.ids.add(values["name"])
            if values.get("href"):
                self.links.append(values["href"])

def check(pages, start, assets):
    errors, reached, pending = set(), set(), deque([start])
    for path, page in pages.items():
        for href in page.links:
            url = urlsplit(urljoin("https://docs.invalid" + path, href))
            if url.netloc != "docs.invalid" or url.scheme != "https":
                continue
            target = unquote(url.path)
            if target not in pages:
                if target not in assets:
                    errors.add(f"{path} -> {href}: missing target")
            elif url.fragment and unquote(url.fragment) not in pages[target].ids:
                errors.add(f"{path} -> {href}: missing anchor")
    while pending:
        path = pending.popleft()
        if path in reached or path not in pages:
            continue
        reached.add(path)
        for href in pages[path].links:
            url = urlsplit(urljoin("https://docs.invalid" + path, href))
            if url.netloc == "docs.invalid" and url.scheme == "https":
                pending.append(unquote(url.path))
    for path in pages.keys() - reached:
        errors.add(f"{path}: unreachable from {start}")
    if errors:
        raise SystemExit("\n".join(sorted(errors)))
    print(f"{len(reached)} reachable pages; internal link and heading targets passed")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--next", action="store_true", help="Next.js app directory")
    parser.add_argument("--markdown", action="store_true", help="README and docs/**/*.md")
    args = parser.parse_args()
    root = args.root.resolve()
    pages, assets = {}, set()
    if args.next:
        built = root / ".next/server/app"
        routes = {"/": built / "index.html"}
        for source in (root / "content/docs").rglob("*.mdx"):
            parts = list(source.relative_to(root / "content").with_suffix("").parts)
            if parts[-1] == "index":
                parts.pop()
            path = "/".join(parts)
            routes["/" + path] = built / (path + ".html")
        for route, file in routes.items():
            pages[route] = Page(file.read_text())
        assets.update("/" + p.relative_to(root / "public").as_posix() for p in (root / "public").rglob("*") if p.is_file())
        # Route handlers also produce non-HTML resources at build time.
        assets.update("/" + str(p.relative_to(built))[:-5] for p in built.rglob("*.body"))
        start = "/"
    elif args.markdown:
        import markdown
        from pymdownx.slugs import slugify
        for file in [root / "README.md", *sorted((root / "docs").rglob("*.md"))]:
            html = markdown.markdown(file.read_text(), extensions=["toc", "tables", "fenced_code"],
                                     extension_configs={"toc": {"slugify": slugify(case="lower")}})
            pages["/" + file.relative_to(root).as_posix()] = Page(html)
        assets.update("/" + str(p.relative_to(root)) for p in root.rglob("*") if p.is_file())
        start = "/README.md"
    else:
        for file in root.rglob("*"):
            if file.is_file():
                path = "/" + file.relative_to(root).as_posix()
                if file.suffix == ".html" and path != "/404.html":
                    pages[path] = Page(file.read_text())
                else:
                    assets.add(path)
        start = "/index.html"
    if start not in pages:
        raise SystemExit(f"Missing landing page {start}")
    check(pages, start, assets)

if __name__ == "__main__":
    main()
